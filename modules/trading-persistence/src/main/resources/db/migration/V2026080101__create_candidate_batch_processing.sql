create schema if not exists trading;

create table trading.candidate_batch_processing (
    batch_id uuid primary key,
    evaluation_id uuid not null,
    source_created_at timestamptz not null,
    status varchar(16) not null,
    failure_reason varchar(512),
    started_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint candidate_batch_processing_status_check
        check (status in ('PROCESSING', 'COMPLETED', 'FAILED'))
);

create index candidate_batch_processing_evaluation_idx
    on trading.candidate_batch_processing (evaluation_id);
