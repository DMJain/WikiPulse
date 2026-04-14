#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="${SERVICE_NAME:-wikipulse-worker}"
NAMESPACE="${NAMESPACE:-default}"
LOCAL_PORT="${LOCAL_PORT:-18080}"
TARGET_PORT="${TARGET_PORT:-8080}"
CONCURRENCY="${CONCURRENCY:-40}"
DURATION_SECONDS="${DURATION_SECONDS:-0}"
ENDPOINT="${ENDPOINT:-/api/analytics/trend?timeframe=1h&project=all}"

ok=0
fail=0
batch=0
port_forward_pid=""

cleanup() {
  if [[ -n "${port_forward_pid}" ]] && kill -0 "${port_forward_pid}" >/dev/null 2>&1; then
    kill "${port_forward_pid}" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

echo "[load-test] Starting port-forward: svc/${SERVICE_NAME} ${LOCAL_PORT}:${TARGET_PORT}"
kubectl -n "${NAMESPACE}" port-forward "svc/${SERVICE_NAME}" "${LOCAL_PORT}:${TARGET_PORT}" >/tmp/wikipulse-k8s-load-port-forward.log 2>&1 &
port_forward_pid=$!

for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:${LOCAL_PORT}/actuator/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${LOCAL_PORT}/actuator/health" >/dev/null 2>&1; then
  echo "[load-test] Worker health endpoint is unreachable through port-forward."
  echo "[load-test] Port-forward log: /tmp/wikipulse-k8s-load-port-forward.log"
  exit 1
fi

echo "[load-test] Target URL: http://127.0.0.1:${LOCAL_PORT}${ENDPOINT}"
echo "[load-test] Concurrency per batch: ${CONCURRENCY}"
if [[ "${DURATION_SECONDS}" != "0" ]]; then
  echo "[load-test] Duration: ${DURATION_SECONDS}s"
else
  echo "[load-test] Duration: infinite (Ctrl+C to stop)"
fi

start_time=$(date +%s)

while true; do
  pids=()
  for ((i = 0; i < CONCURRENCY; i++)); do
    (
      curl -fsS "http://127.0.0.1:${LOCAL_PORT}${ENDPOINT}" >/dev/null 2>&1
    ) &
    pids+=("$!")
  done

  for pid in "${pids[@]}"; do
    if wait "${pid}"; then
      ok=$((ok + 1))
    else
      fail=$((fail + 1))
    fi
  done

  batch=$((batch + 1))
  if ((batch % 10 == 0)); then
    now=$(date +%s)
    elapsed=$((now - start_time))
    echo "[load-test] elapsed=${elapsed}s ok=${ok} fail=${fail}"
  fi

  if [[ "${DURATION_SECONDS}" != "0" ]]; then
    now=$(date +%s)
    elapsed=$((now - start_time))
    if ((elapsed >= DURATION_SECONDS)); then
      break
    fi
  fi
done

echo "[load-test] Complete: ok=${ok} fail=${fail}"