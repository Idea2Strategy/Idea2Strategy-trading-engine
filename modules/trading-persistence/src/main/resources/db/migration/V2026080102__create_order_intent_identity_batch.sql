create table trading.order_intent_batch (
    batch_id uuid primary key,
    evaluation_id uuid not null,
    bot_id uuid not null,
    source_candidate_batch_id uuid not null,
    request_fingerprint char(64) not null,
    created_at timestamptz not null default current_timestamp,
    constraint order_intent_batch_evaluation_key unique (evaluation_id),
    constraint order_intent_batch_source_candidate_batch_key unique (source_candidate_batch_id),
    constraint order_intent_batch_request_fingerprint_check
        check (request_fingerprint ~ '^[0-9a-f]{64}$')
);

create table trading.order_intent_identity (
    intent_id uuid primary key,
    batch_id uuid not null,
    candidate_id uuid not null,
    ordinal integer not null,
    created_at timestamptz not null default current_timestamp,
    constraint order_intent_identity_batch_fk
        foreign key (batch_id) references trading.order_intent_batch (batch_id) on delete cascade,
    constraint order_intent_identity_candidate_key unique (candidate_id),
    constraint order_intent_identity_batch_ordinal_key unique (batch_id, ordinal),
    constraint order_intent_identity_ordinal_check check (ordinal >= 0)
);
