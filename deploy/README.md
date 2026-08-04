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

trading-worker requires a PostgreSQL schema migrated by the root migration owner, a Redis endpoint
sharing the gateway key prefix, and a validated warm-up bundle rooted at
TRADING_WARMUP_BUNDLE_ROOT. Flyway stays disabled in the runtime.

The current repository has file-backed mapping, rights-evidence, and warm-up adapters. Deployment
must materialize those files before container start. Direct S3-backed refresh is not implemented and
must not be implied by setting an S3 URI.

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

