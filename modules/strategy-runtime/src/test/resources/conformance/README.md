# Pinned cross-runtime conformance fixture (card D92, test input only)

`strategy-bot-runtime/v1/basic-executor-conformance.v1.json` is a **byte-exact, digest-pinned
copy** of the language-neutral fixture owned by the `backtest-engine` repository at
`conformance/strategy-bot-runtime/v1/`. One set of bytes is consumed by two languages: the Python
backtest runtime asserts it in `tests/test_d92_runtime_conformance.py`, the Java trading runtime in
`BasicExecutorConformanceTest`. That is what turns "the two runtimes are semantically equivalent"
from a claim into a test result.

This copy is **not** a second source of truth. The fixture is authored and versioned in
`backtest-engine`; this directory only exists because trading-engine CI checks out no sibling
repositories.

## Drift control

`basic-executor-conformance.v1.json.sha256` records the SHA-256 of the fixture bytes, identical to
the digest recorded next to the original. `BasicExecutorConformanceTest` recomputes the digest from
the raw resource bytes and compares it against both the recorded file and the constant in the test
before parsing anything, so an edit on either side fails both bindings instead of drifting quietly.
The repository `.gitattributes` marks this directory `-text` because a CRLF rewrite would change the
bytes both sides must agree on.

## Refreshing

Refresh only when `backtest-engine` publishes a new fixture revision, and copy the fixture, its
`.sha256`, and the digest constant in `BasicExecutorConformanceTest` in the same commit. Never
hand-edit the fixture here: per `conformance/README.md` in `backtest-engine`, a disputed case is
argued with D and changed at the source.
