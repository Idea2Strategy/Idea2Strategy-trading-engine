#!/usr/bin/env bash
set -euo pipefail

platform="${1:-linux/amd64}"
suffix="${platform##*/}"

for application in market-gateway trading-worker; do
  image="idea2strategy/${application}:runtime-smoke-${suffix}"
  cache_args=()
  if [[ -n "${BUILDX_CACHE_SCOPE_PREFIX:-}" ]]; then
    cache_scope="${BUILDX_CACHE_SCOPE_PREFIX}-${application}-${suffix}"
    cache_args+=(
      --cache-from "type=gha,scope=${cache_scope}"
      --cache-to "type=gha,mode=max,scope=${cache_scope}"
    )
  fi
  docker buildx build \
    --platform "${platform}" \
    "${cache_args[@]}" \
    --load \
    --tag "${image}" \
    --file "apps/${application}/Dockerfile" \
    .

  test "$(docker image inspect "${image}" --format '{{.Config.User}}')" = "10001:10001"
  test "$(docker image inspect "${image}" --format '{{.Config.StopSignal}}')" = "SIGTERM"
  test "$(docker image inspect "${image}" --format '{{json .Config.Entrypoint}}')" \
    = '["java","-jar","/opt/idea2strategy/application.jar"]'
  docker image inspect "${image}" --format '{{json .Config.Healthcheck.Test}}' \
    | grep -F 'I2S_READINESS_FILE' >/dev/null

  test "$(docker run --rm --platform "${platform}" --entrypoint id "${image}" -u)" = "10001"
  MSYS_NO_PATHCONV=1 docker run --rm --platform "${platform}" --workdir /opt/idea2strategy \
    --entrypoint /usr/bin/test "${image}" -r application.jar
  docker run --rm --platform "${platform}" --entrypoint java "${image}" -version
done
