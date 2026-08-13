# Trading migration contributions

This directory is the machine-readable handoff boundary from the trading engine to the central Flyway bundle.
The central bundle reads `contribution.properties` from the exact pinned trading-engine commit. Its keys are fixed:

- `contract.version`: contract format version; currently `1`.
- `owner`: migration filename owner; exactly `trading`.
- `schemas`: schemas containing trading-owned entities; `bot,trading`. The central ownership verifier remains authoritative for table-level ownership inside those schemas.
- `migrations.directory`: only this directory may contribute production Flyway SQL.
- `fixtures.directory`: test-only examples that the central bundle must never scan as migrations.
- `filename.regex`: complete filename contract for every contributed SQL file.
- `runtime.flyway.enabled`: must remain `false`; migration execution belongs to the central one-shot deployment step.

New canonical changes go through the following boundary:

1. Start from the canonical root `db/schema.dbml` and the central immutable baseline.
2. Put only a reviewed, forward-only change in `migrations/` using `VyyyyMMddHHmmss__trading_description.sql`, where the timestamp is UTC and globally unique.
3. The central assembler verifies the pinned commit, owner, filename, digest, ordering, and table-level ownership before copying the contribution into its immutable bundle.
4. The trading application starts only after that bundle has migrated and validated the database. It never invokes Flyway itself and Hibernate remains `validate`-only.

The launch schema was rebased on 2026-08-13. All trading changes through that point are included in the immutable central `V1__initial_schema.sql`, so `migrations/` currently contains no timestamped migration. New development resumes with a fresh UTC-timestamped migration; the contribution contract and central Flyway assembly remain unchanged.

The existing `modules/trading-persistence/src/main/resources/db/migration` files are private compatibility migrations for the pre-canonical persistence tests. They are intentionally preserved for F01-F14 reconstruction, but they are not canonical contributions and must not be copied into this directory or the central bundle.
