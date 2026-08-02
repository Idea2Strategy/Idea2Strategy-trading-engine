create table trading.execution_corporate_action_application(
    action_id uuid primary key,instrument_id uuid not null,action_type varchar(16) not null,
    numerator bigint not null,denominator bigint not null,effective_at timestamptz not null,
    approval_id uuid not null unique,approved_by_operator_id uuid not null,approved_at timestamptz not null,
    evidence_digest varchar(64) not null,policy_version varchar(64) not null,request_fingerprint varchar(64) not null,
    adjusted_lots integer not null,adjusted_flow_positions integer not null,
    ledger_effect varchar(64) not null,applied_at timestamptz not null,
    check(action_type='SPLIT'),check(numerator>0 and denominator>0),
    check(evidence_digest ~ '^[0-9a-f]{64}$' and request_fingerprint ~ '^[0-9a-f]{64}$'),
    check(adjusted_lots>=0 and adjusted_flow_positions>=0)
);
create table trading.execution_corporate_action_lot_adjustment(
    action_id uuid not null references trading.execution_corporate_action_application(action_id),
    lot_id uuid not null references trading.execution_position_lot(lot_id),
    before_quantity numeric(38,18) not null,after_quantity numeric(38,18) not null,
    preserved_cost_basis numeric(38,18) not null,adjusted_unit_cost numeric(38,18) not null,
    projection_version bigint not null,primary key(action_id,lot_id),
    check(before_quantity>0 and after_quantity>0 and preserved_cost_basis>=0 and adjusted_unit_cost>=0)
);
