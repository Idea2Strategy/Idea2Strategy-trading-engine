# Trading runtime deployment inputs

Both applications are non-HTTP Spring Boot processes. Do not publish a host port or probe an
Actuator endpoint. Their container health check combines Java PID 1 liveness with a semantic
readiness marker.

## Commands and readiness

| Runtime | Image entrypoint | Ready when | Becomes unready |
| --- | --- | --- | --- |
| market-gateway | java -jar /opt/idea2strategy/application.jar | Current provider rights and credentials have passed validation and Alpaca has confirmed the requested SIP subscription. | Provider disconnect/error, rights failure, or SIGTERM. |
| trading-worker | java -jar /opt/idea2strategy/application.jar | Restart recovery has completed and the intake gate has opened. | SIGTERM closes intake before the configured drain and final recovery. |

The Docker health command is:

    test -s "$I2S_READINESS_FILE" && kill -0 1

The image entrypoint is exec form and STOPSIGNAL is SIGTERM, so Java remains PID 1 and Spring's
shutdown event runs. Set the orchestrator stop timeout to at least 45 seconds; the worker's default
intake-drain timeout is 30 seconds.

## Required runtime inputs

Copy the applicable .env.example and replace blank secret values only through the runtime secret
provider. The examples contain no credentials.

market-gateway additionally requires these read-only files:

- the instrument mapping at MARKET_GATEWAY_INSTRUMENT_MAPPING_PATH;
- current Alpaca SIP rights evidence at MARKET_GATEWAY_RIGHTS_EVIDENCE_PATH.

The development AWS secret metadata names are `idea2strategy-dev/backtest/alpaca` for
`ALPACA_API_KEY` and `idea2strategy-dev/backtest/alpaca-secret` for `ALPACA_API_SECRET`. The
names are deployment metadata identifiers; their `backtest` segment does not grant broader access or
change the Trading consumer. The host may read only these exact secrets and must inject their values
through a root-owned mode-0600 runtime file or
an equivalent one-shot runtime mechanism. The application never calls Secrets Manager. Do not put
secret values in Terraform state, user data, logs, image layers, or this repository.

trading-worker requires a PostgreSQL schema migrated by the root migration owner, a Redis endpoint
sharing the gateway key prefix, and a validated warm-up bundle rooted at
TRADING_WARMUP_BUNDLE_ROOT. Flyway stays disabled in the runtime.

The current repository has file-backed mapping, rights-evidence, and warm-up adapters. Deployment
must materialize those files before container start. Direct S3-backed refresh is not implemented and
must not be implied by setting an S3 URI. Both processes fail startup unless the host also mounts a
valid materialization receipt at the configured receipt path.

The receipt is Java-properties text with contract `i2s.materialization-receipt`, schema version 1,
and a positive `artifact-count`. Every zero-based `artifact.N` entry has nonblank `id`,
`source-bucket`, `source-key`, immutable `source-version-id`, lowercase SHA-256, and absolute
`local-path` fields. The gateway requires IDs `instrument-mapping` and `provider-rights`; the worker
requires `warmup-manifest`, while every downloaded warm-up object must also be listed so its checksum
is verified before any bundle adapter opens it. IDs and local paths must be unique. Receipt and
artifacts must be regular files rather than symlinks. Example:

    contract=i2s.materialization-receipt
    schema-version=1
    artifact-count=1
    artifact.0.id=warmup-manifest
    artifact.0.source-bucket=idea2strategy-runtime
    artifact.0.source-key=trading/warmup/manifest.json
    artifact.0.source-version-id=EXACT_S3_VERSION_ID
    artifact.0.sha256=64_lowercase_hex_characters
    artifact.0.local-path=/run/idea2strategy/trading-worker/warmup/manifest.json

The host pre-start materializer must fetch each exact S3 version, verify the expected checksum,
write files and receipt into a new directory, then atomically replace the mounted directory. Mount
the completed directory read-only. A missing version, missing required ID, checksum mismatch,
duplicate ID/path, symlink, or altered file is a fail-closed startup error; the readiness marker is
never created.

Worker IDs and Redis consumer names must be stable and unique per replica. Reusing one identity
between simultaneous replicas makes operational ownership ambiguous; generating a new identity on
every restart makes pending-entry diagnosis unnecessarily difficult.

## Development host resource floor

For the two containers sharing one t4g.medium, start with:

| Runtime | Memory limit | CPU limit | PIDs limit |
| --- | ---: | ---: | ---: |
| market-gateway | 768 MiB | 0.50 | 256 |
| trading-worker | 2304 MiB | 1.25 | 384 |

This leaves roughly 1 GiB and 0.25 vCPU for the operating system, Docker, and monitoring. The
examples cap the Java heap ergonomically at 70% of each cgroup memory limit. Increase the instance
or adjust limits only from measured memory, GC, CPU-credit, lag, and latency evidence.

Mount mapping, rights, and warm-up inputs read-only. Use read_only, a writable /tmp tmpfs,
no-new-privileges, and the image's non-root UID/GID 10001:10001. Do not grant the Docker socket,
host network, privileged mode, or Linux capabilities.
