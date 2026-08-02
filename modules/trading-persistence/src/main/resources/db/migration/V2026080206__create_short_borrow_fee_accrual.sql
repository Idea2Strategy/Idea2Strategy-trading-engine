create schema if not exists trading;

create table trading.short_borrow_fee_accrual (
    accrual_id uuid primary key,
    lot_id uuid not null,
    instrument_id uuid not null,
    bot_id uuid not null,
    accrual_date date not null,
    open_quantity numeric(38, 18) not null check (open_quantity > 0),
    reference_price numeric(38, 18) not null check (reference_price > 0),
    notional_amount numeric(38, 18) not null check (notional_amount > 0),
    annual_borrow_rate numeric(38, 18) not null check (annual_borrow_rate >= 0),
    fee_amount numeric(38, 18) not null check (fee_amount >= 0),
    currency varchar(12) not null,
    day_count_convention varchar(32) not null,
    policy_version varchar(200) not null,
    rate_snapshot_version varchar(200) not null,
    accrued_at timestamptz not null,
    constraint uq_short_borrow_fee_lot_day unique (lot_id, accrual_date)
);

create index ix_short_borrow_fee_lot_date
    on trading.short_borrow_fee_accrual (lot_id, accrual_date);
create index ix_short_borrow_fee_bot_date
    on trading.short_borrow_fee_accrual (bot_id, accrual_date);

comment on table trading.short_borrow_fee_accrual is
    'Append-only daily short-lot borrow fee accrual with explicit rate and policy evidence.';
