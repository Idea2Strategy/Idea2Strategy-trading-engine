# Pinned canonical baseline (test input only)

This directory is a **byte-exact, digest-pinned copy** of the central Flyway bundle that the root
superproject assembles from the backend migration source and the trading contributions in
`db/migration-contributions`. It exists for one reason: the trading engine owns no canonical DDL, so
without a local copy its own tests can never stand up the canonical schema they write to.

It is **not** a second source of truth.

- The canonical model is `db/schema.dbml` in the root superproject.
- The canonical migrations are owned by the central bundle. New trading-owned changes still go
  through `db/migration-contributions/migrations` and are assembled centrally.
- Nothing here is ever applied at runtime. Flyway is a `testImplementation` dependency only, the
  trading worker sets `spring.flyway.enabled=false`, `contribution.properties` declares
  `runtime.flyway.enabled=false`, and `apps/trading-worker`'s `verifyRuntimeDatabaseBoundary` Gradle
  task fails the build if any Flyway artifact reaches the runtime classpath.

## Drift control

`baseline.manifest` records a SHA-256 per file, with CRLF normalised to LF so the digests are
portable across checkouts. `baseline.sha256` is the digest of that manifest. The per-file digests are
identical to the entries in the root bundle's `db/flyway-ci-bundle/migration-bundle.manifest`, which
is what makes an accidental edit detectable.

Two checks guard this copy:

1. `CanonicalBaselineContractTest` in `modules/trading-persistence` verifies every digest and then
   migrates the baseline into a real PostgreSQL container, so a corrupted or truncated copy fails the
   trading build.
2. The root superproject records `canonical_baseline_sha256` in
   `db/flyway-ci-bundle/source-revisions.json` and its assembler `scripts/prepare-flyway-bundle.ps1`
   compares the freshly assembled bundle against this pinned copy at the exact submodule revision.
   That is the only place where both sides are present at once, because root CI deliberately checks
   out without submodules and must stay credential-free.

## Refreshing

Refresh this directory only after the central bundle changes, and in this order:

1. Contribute the canonical migration through `db/migration-contributions/migrations`.
2. Let the root superproject assemble and merge the new bundle.
3. Copy the assembled bundle here, regenerate `baseline.manifest` and `baseline.sha256`, and update
   the root `canonical_baseline_sha256` in the same pointer change.

Never hand-edit a file in this directory.
