# Migration contract fixtures

Files here exist only to test contribution discovery, rejection behavior and canonical contracts. The central Flyway assembler must never read this directory as production migration input, and fixtures must not use the `.sql` extension.

- `V20990101000000__trading_contract_probe.sql.fixture` proves that a fixture is never discovered as a migration.
- `partial_fill_allocation_contract.sql.fixture` proves the partial-fill allocation invariants of the canonical write side.
- `trading_read_projection_contract.sql.fixture` proves the F15 canonical read projections. It seeds a focused bot, two partitions and three flows, then prepares and executes the exact statements shipped in `CanonicalTradingReadSql` to assert owner, bot, partition and flow isolation, individual fill preservation, official ledger balance, reason coverage and deterministic ordering and paging.

The read contract fixture is the only executable proof of these queries: the trading engine owns no canonical DDL, so its own test database can never hold the canonical read model. `CanonicalTradingReadContractTest` fails the trading build whenever the shipped statements and this fixture drift apart, and the central Flyway integration job runs the fixture against a real migrated canonical database.
