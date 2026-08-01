create table trading.trading_order (
    order_id uuid primary key,
    create_command_id uuid not null unique,
    request_fingerprint varchar(64) not null,
    intent_id uuid not null unique,
    candidate_id uuid not null unique,
    instrument_id uuid not null,
    side varchar(4) not null,
    quantity numeric(38,18) not null,
    order_type varchar(13) not null,
    time_in_force varchar(3) not null,
    limit_price numeric(38,18),
    stop_price numeric(38,18),
    trail_percent numeric(38,18),
    expires_at timestamptz,
    status varchar(18) not null,
    cumulative_filled_quantity numeric(38,18) not null,
    version bigint not null,
    created_at timestamptz not null,
    last_transition_at timestamptz not null,
    terminal_reason text,
    constraint trading_order_request_fingerprint_check
        check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint trading_order_side_check
        check (side in ('BUY', 'SELL')),
    constraint trading_order_type_check
        check (order_type in ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT', 'TRAILING_STOP')),
    constraint trading_order_time_in_force_check
        check (time_in_force in ('DAY', 'GTC', 'GTD')),
    constraint trading_order_status_check
        check (status in ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')),
    constraint trading_order_quantity_check
        check (quantity > 0),
    constraint trading_order_price_values_check
        check (
            (limit_price is null or limit_price > 0)
            and (stop_price is null or stop_price > 0)
            and (trail_percent is null or (trail_percent > 0 and trail_percent <= 1))
        ),
    constraint trading_order_type_fields_check
        check (
            (order_type = 'MARKET' and limit_price is null and stop_price is null and trail_percent is null)
            or (order_type = 'LIMIT' and limit_price is not null and stop_price is null and trail_percent is null)
            or (order_type = 'STOP' and limit_price is null and stop_price is not null and trail_percent is null)
            or (order_type = 'STOP_LIMIT' and limit_price is not null and stop_price is not null and trail_percent is null)
            or (order_type = 'TRAILING_STOP' and limit_price is null and stop_price is null and trail_percent is not null)
        ),
    constraint trading_order_tif_expiry_check
        check (
            (time_in_force = 'GTD' and expires_at is not null and expires_at > created_at)
            or (time_in_force <> 'GTD' and expires_at is null)
        ),
    constraint trading_order_fill_bounds_check
        check (cumulative_filled_quantity >= 0 and cumulative_filled_quantity <= quantity),
    constraint trading_order_version_check
        check (version > 0),
    constraint trading_order_transition_time_check
        check (last_transition_at >= created_at),
    constraint trading_order_state_shape_check
        check (
            (status = 'ACCEPTED'
                and version = 1
                and cumulative_filled_quantity = 0
                and last_transition_at = created_at
                and terminal_reason is null)
            or (status = 'REJECTED'
                and version = 1
                and cumulative_filled_quantity = 0
                and last_transition_at = created_at
                and terminal_reason is not null
                and btrim(terminal_reason) <> '')
            or (status = 'PARTIALLY_FILLED'
                and version >= 2
                and cumulative_filled_quantity > 0
                and cumulative_filled_quantity < quantity
                and terminal_reason is null)
            or (status = 'FILLED'
                and version >= 2
                and cumulative_filled_quantity = quantity
                and terminal_reason is null)
            or (status in ('CANCELLED', 'EXPIRED')
                and cumulative_filled_quantity < quantity
                and ((cumulative_filled_quantity = 0 and version = 2)
                    or (cumulative_filled_quantity > 0 and version >= 3))
                and terminal_reason is not null
                and btrim(terminal_reason) <> '')
        )
);

create table trading.order_lifecycle_command (
    command_id uuid primary key,
    request_fingerprint varchar(64) not null,
    order_id uuid not null references trading.trading_order(order_id),
    resulting_version bigint not null,
    result_status varchar(18) not null,
    constraint order_lifecycle_command_request_fingerprint_check
        check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint order_lifecycle_command_version_check
        check (resulting_version > 0),
    constraint order_lifecycle_command_status_check
        check (result_status in ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')),
    constraint order_lifecycle_command_result_shape_check
        check (
            (resulting_version = 1 and result_status in ('ACCEPTED', 'REJECTED'))
            or (resulting_version > 1 and result_status in ('PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED'))
        ),
    constraint order_lifecycle_command_order_version_unique
        unique (order_id, resulting_version),
    constraint order_lifecycle_command_receipt_identity_unique
        unique (command_id, order_id, resulting_version, result_status)
);

create table trading.order_lifecycle_transition (
    order_id uuid not null references trading.trading_order(order_id),
    version bigint not null,
    command_id uuid not null unique,
    from_status varchar(18),
    to_status varchar(18) not null,
    fill_delta numeric(38,18),
    cumulative_filled_quantity numeric(38,18) not null,
    occurred_at timestamptz not null,
    reason text,
    primary key (order_id, version),
    constraint order_lifecycle_transition_version_check
        check (version > 0),
    constraint order_lifecycle_transition_from_status_check
        check (from_status is null or from_status in ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')),
    constraint order_lifecycle_transition_to_status_check
        check (to_status in ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED')),
    constraint order_lifecycle_transition_fill_check
        check (fill_delta is null or fill_delta > 0),
    constraint order_lifecycle_transition_cumulative_check
        check (cumulative_filled_quantity >= 0),
    constraint order_lifecycle_transition_receipt_match
        foreign key (command_id, order_id, version, to_status)
        references trading.order_lifecycle_command(command_id, order_id, resulting_version, result_status),
    constraint order_lifecycle_transition_initial_shape_check
        check (
            (version = 1
                and from_status is null
                and to_status in ('ACCEPTED', 'REJECTED')
                and cumulative_filled_quantity = 0)
            or (version = 2
                and from_status = 'ACCEPTED'
                and to_status in ('PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED'))
            or (version >= 3
                and from_status = 'PARTIALLY_FILLED'
                and to_status in ('PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'EXPIRED'))
        ),
    constraint order_lifecycle_transition_kind_shape_check
        check (
            (to_status in ('PARTIALLY_FILLED', 'FILLED') and fill_delta is not null and reason is null)
            or (to_status = 'ACCEPTED' and version = 1 and fill_delta is null and reason is null)
            or (to_status in ('CANCELLED', 'EXPIRED', 'REJECTED')
                and fill_delta is null and reason is not null and btrim(reason) <> '')
        ),
    constraint order_lifecycle_transition_fill_state_check
        check (
            (to_status in ('ACCEPTED', 'REJECTED') and cumulative_filled_quantity = 0)
            or (to_status in ('PARTIALLY_FILLED', 'FILLED')
                and ((from_status = 'ACCEPTED' and cumulative_filled_quantity = fill_delta)
                    or (from_status = 'PARTIALLY_FILLED' and cumulative_filled_quantity > fill_delta)))
            or (to_status in ('CANCELLED', 'EXPIRED')
                and ((from_status = 'ACCEPTED' and cumulative_filled_quantity = 0)
                    or (from_status = 'PARTIALLY_FILLED' and cumulative_filled_quantity > 0)))
        )
);
