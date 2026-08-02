create schema if not exists trading;

create table trading.official_ledger_transaction (
    transaction_id uuid primary key,
    source_event_id uuid not null unique,
    posted_at timestamptz not null,
    posting_kind varchar(16) not null check (posting_kind in ('STANDARD', 'REVERSAL', 'CORRECTION')),
    reverses_transaction_id uuid references trading.official_ledger_transaction(transaction_id),
    corrects_transaction_id uuid references trading.official_ledger_transaction(transaction_id),
    created_at timestamptz not null default now(),
    check (
        (posting_kind = 'STANDARD' and reverses_transaction_id is null and corrects_transaction_id is null) or
        (posting_kind = 'REVERSAL' and reverses_transaction_id is not null and corrects_transaction_id is null) or
        (posting_kind = 'CORRECTION' and reverses_transaction_id is null and corrects_transaction_id is not null)
    ),
    unique (transaction_id, source_event_id)
);

create unique index official_ledger_one_reversal_per_transaction
    on trading.official_ledger_transaction(reverses_transaction_id)
    where reverses_transaction_id is not null;

create table trading.official_ledger_entry (
    entry_id uuid primary key,
    transaction_id uuid not null references trading.official_ledger_transaction(transaction_id),
    entry_sequence integer not null check (entry_sequence > 0),
    account_code varchar(128) not null check (length(trim(account_code)) > 0),
    direction varchar(8) not null check (direction in ('DEBIT', 'CREDIT')),
    currency char(3) not null check (currency ~ '^[A-Z]{3}$'),
    amount numeric(38,18) not null check (amount > 0),
    source_event_id uuid not null,
    foreign key (transaction_id, source_event_id)
        references trading.official_ledger_transaction(transaction_id, source_event_id),
    unique (transaction_id, entry_sequence),
    unique (transaction_id, entry_id)
);

create index official_ledger_entry_account_idx
    on trading.official_ledger_entry(account_code, currency);

create table trading.official_ledger_command_receipt (
    command_id uuid primary key,
    request_fingerprint char(64) not null,
    transaction_id uuid not null references trading.official_ledger_transaction(transaction_id),
    completed_at timestamptz not null default now()
);

create or replace function trading.reject_official_ledger_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'official ledger is append-only';
end;
$$;

create trigger official_ledger_transaction_immutable
before update or delete on trading.official_ledger_transaction
for each row execute function trading.reject_official_ledger_mutation();

create trigger official_ledger_entry_immutable
before update or delete on trading.official_ledger_entry
for each row execute function trading.reject_official_ledger_mutation();

create trigger official_ledger_command_receipt_immutable
before update or delete on trading.official_ledger_command_receipt
for each row execute function trading.reject_official_ledger_mutation();

create or replace function trading.validate_official_ledger_balance()
returns trigger language plpgsql as $$
declare
    target_transaction_id uuid;
begin
    target_transaction_id := case
        when tg_table_name = 'official_ledger_transaction' then new.transaction_id
        else new.transaction_id
    end;
    if (select count(*) from trading.official_ledger_entry
        where transaction_id = target_transaction_id) < 2 then
        raise exception 'official ledger transaction requires at least two entries';
    end if;
    if exists (
        select currency
        from trading.official_ledger_entry
        where transaction_id = target_transaction_id
        group by currency
        having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0
    ) then
        raise exception 'official ledger transaction must balance by currency';
    end if;
    return null;
end;
$$;

create constraint trigger official_ledger_transaction_balanced
after insert on trading.official_ledger_transaction
deferrable initially deferred
for each row execute function trading.validate_official_ledger_balance();

create constraint trigger official_ledger_entry_balanced
after insert on trading.official_ledger_entry
deferrable initially deferred
for each row execute function trading.validate_official_ledger_balance();
