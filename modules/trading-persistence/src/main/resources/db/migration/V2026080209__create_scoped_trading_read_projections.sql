create table trading.execution_order_scope (
    order_id uuid primary key references trading.trading_order(order_id),
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    attributed_at timestamptz not null,
    unique (order_id, bot_id, partition_id, flow_id)
);

create index execution_order_scope_owner_idx
    on trading.execution_order_scope(bot_id, partition_id, flow_id, order_id);

create table trading.execution_ledger_scope (
    transaction_id uuid primary key references trading.official_ledger_transaction(transaction_id),
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    order_id uuid,
    attributed_at timestamptz not null,
    foreign key (order_id, bot_id, partition_id, flow_id)
        references trading.execution_order_scope(order_id, bot_id, partition_id, flow_id)
);

create index execution_ledger_scope_owner_idx
    on trading.execution_ledger_scope(bot_id, partition_id, flow_id, transaction_id);

create function trading.enforce_ledger_scope_lineage()
returns trigger
language plpgsql
as $$
declare
    source_transaction_id uuid;
    source_scope trading.execution_ledger_scope%rowtype;
begin
    select coalesce(reverses_transaction_id, corrects_transaction_id)
    into source_transaction_id
    from trading.official_ledger_transaction
    where transaction_id = new.transaction_id;

    if source_transaction_id is null then
        return new;
    end if;

    select * into source_scope
    from trading.execution_ledger_scope
    where transaction_id = source_transaction_id;

    if not found then
        raise exception 'source ledger transaction must be attributed first'
            using errcode = '23514';
    end if;

    if source_scope.bot_id <> new.bot_id
        or source_scope.partition_id <> new.partition_id
        or source_scope.flow_id <> new.flow_id
        or source_scope.order_id is distinct from new.order_id then
        raise exception 'ledger lineage must preserve execution scope'
            using errcode = '23514';
    end if;

    return new;
end
$$;

create trigger enforce_ledger_scope_lineage
before insert or update on trading.execution_ledger_scope
for each row execute function trading.enforce_ledger_scope_lineage();

create function trading.reject_execution_projection_evidence_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'execution projection evidence is append-only'
        using errcode = '23514';
end
$$;

create trigger execution_order_scope_immutable
before update or delete on trading.execution_order_scope
for each row execute function trading.reject_execution_projection_evidence_mutation();

create trigger execution_ledger_scope_immutable
before update or delete on trading.execution_ledger_scope
for each row execute function trading.reject_execution_projection_evidence_mutation();

create table trading.execution_projection_reason (
    reason_id uuid primary key,
    bot_id uuid not null,
    partition_id uuid not null,
    flow_id uuid not null,
    order_id uuid,
    reason_type varchar(16) not null,
    reason_code varchar(128) not null,
    detail text not null,
    occurred_at timestamptz not null,
    check (reason_type in ('REJECTION', 'RESIZE', 'SETTLEMENT')),
    check (length(btrim(reason_code)) > 0 and length(btrim(detail)) > 0),
    foreign key (order_id, bot_id, partition_id, flow_id)
        references trading.execution_order_scope(order_id, bot_id, partition_id, flow_id)
);

create index execution_projection_reason_owner_time_idx
    on trading.execution_projection_reason(bot_id, partition_id, flow_id, occurred_at, reason_id);

create trigger execution_projection_reason_immutable
before update or delete on trading.execution_projection_reason
for each row execute function trading.reject_execution_projection_evidence_mutation();
