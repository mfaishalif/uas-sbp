#!/usr/bin/env bash
# Corrected persistence orchestration: the second server start must NOT delete
# the database from the seed phase. The first attempt removed it, so this rerun
# proves restart persistence against the same file.
set -uo pipefail
ROOT=/workspaces/uas-sbp
S="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/backend/target/finresolve-backend-0.1.0.jar"
DB=/tmp/finresolve-qa-persistence-rerun.db
PID=""

start() {
  local name=$1 reset=$2
  if [ "$reset" = yes ]; then rm -f "$DB" "$DB-wal" "$DB-shm"; fi
  (
    cd "$ROOT"
    FINRESOLVE_DB_PATH="$DB" FINRESOLVE_DEMO_ENABLED=false \
    FINRESOLVE_SIMILARITY_MODE=baseline SERVER_ADDRESS=127.0.0.1 SERVER_PORT=18219 \
    java -jar "$JAR"
  ) > "$S/server-persistence-rerun-$name.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 240); do
    curl -fsS http://127.0.0.1:18219/api/health >/dev/null 2>&1 && { echo "READY $name pid=$PID"; return 0; }
    kill -0 "$PID" 2>/dev/null || { echo "EXITED $name"; return 1; }
    sleep 0.5
  done
  return 1
}
stop() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null
    for _ in $(seq 1 40); do kill -0 "$PID" 2>/dev/null || break; sleep 0.25; done
    kill -KILL "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    echo "STOP"
  fi
  PID=""
}

start seed yes
node "$ROOT/docs/testing/probes/persistence.mjs" seed http://127.0.0.1:18219 "$S/backend-persistence-state.json" > "$S/backend-persistence-seed.log" 2>&1
echo "seed exit=$?" | tee "$S/persistence-rerun-exit-codes.txt"
stop
start verify no
node "$ROOT/docs/testing/probes/persistence.mjs" verify http://127.0.0.1:18219 "$S/backend-persistence-state.json" "$S/backend-persistence-restart.json" > "$S/backend-persistence-verify.log" 2>&1
echo "verify exit=$?" | tee -a "$S/persistence-rerun-exit-codes.txt"
node "$ROOT/docs/testing/probes/persistence.mjs" busy http://127.0.0.1:18219 "$DB" "$S/backend-persistence-busy.json" > "$S/backend-persistence-busy.log" 2>&1
echo "busy exit=$?" | tee -a "$S/persistence-rerun-exit-codes.txt"
stop
sha256sum "$DB" >> "$S/persistence-rerun-exit-codes.txt"
cat "$S/backend-persistence-verify.log"
cat "$S/backend-persistence-busy.log"
