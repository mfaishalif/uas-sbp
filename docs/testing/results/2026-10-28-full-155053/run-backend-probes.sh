#!/usr/bin/env bash
# Re-runs the committed backend exploratory probes against the JAR built by
# `npm run verify`. Every session gets a fresh SQLite database under /tmp; the
# repository database is never opened. Evidence is written into this directory.
set -uo pipefail

ROOT=/workspaces/uas-sbp
SESSION_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/backend/target/finresolve-backend-0.1.0.jar"
TMP=/tmp/finresolve-qa-$(date +%s)
mkdir -p "$TMP"
JAVA_PID=""
ALL_PIDS=()
STATUS=0

kill_tree() {
  local pid=$1
  [ -z "$pid" ] && return 0
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill -TERM "$pid" 2>/dev/null || true
}

cleanup() {
  for pid in "${ALL_PIDS[@]}"; do kill_tree "$pid"; done
}
trap cleanup EXIT

start_server() {
  local name=$1 port=$2 db=$3 mode=${4:-baseline}
  rm -f "$db" "$db-wal" "$db-shm"
  echo "START $name port=$port db=$db mode=$mode"
  (
    cd "$ROOT"
    FINRESOLVE_DB_PATH="$db" \
    FINRESOLVE_DEMO_ENABLED=false \
    FINRESOLVE_SIMILARITY_MODE="$mode" \
    SERVER_ADDRESS=127.0.0.1 \
    SERVER_PORT="$port" \
    java -jar "$JAR"
  ) > "$SESSION_DIR/server-$name.log" 2>&1 &
  JAVA_PID=$!
  ALL_PIDS+=("$JAVA_PID")
  for _ in $(seq 1 240); do
    if curl -fsS "http://127.0.0.1:$port/api/health" >/dev/null 2>&1; then
      echo "READY $name pid=$JAVA_PID"
      return 0
    fi
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then
      echo "SERVER EXITED $name" >&2
      tail -40 "$SESSION_DIR/server-$name.log" >&2
      return 1
    fi
    sleep 0.5
  done
  echo "SERVER TIMEOUT $name" >&2
  return 1
}

stop_server() {
  local name=$1
  if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
    kill_tree "$JAVA_PID"
    for _ in $(seq 1 40); do
      kill -0 "$JAVA_PID" 2>/dev/null || break
      sleep 0.25
    done
    kill -KILL "$JAVA_PID" 2>/dev/null || true
    wait "$JAVA_PID" 2>/dev/null || true
    echo "STOP $name"
  fi
  JAVA_PID=""
}

record() {
  local label=$1 code=$2
  echo "$label exit=$code" | tee -a "$SESSION_DIR/probe-exit-codes.txt"
  [ "$code" -ne 0 ] && STATUS=1
  return 0
}

seed_sample() {
  local port=$1
  curl -sS -o "$SESSION_DIR/seed-sample-$port.json" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$port/api/imports/cfpb" \
    -F "file=@$ROOT/data/cfpb/sample.csv;type=text/csv" \
    -F 'metadata={"archiveUrl":"https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip","sourceFileName":"finresolve-sample.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json' \
    >> "$SESSION_DIR/seed-http.txt"
  echo >> "$SESSION_DIR/seed-http.txt"
}

# 1. Unicode integrity, empty database.
start_server unicode 18210 "$TMP/unicode.db" baseline
node "$ROOT/docs/testing/probes/unicode-integrity.mjs" http://127.0.0.1:18210 "$TMP/unicode.db" "$SESSION_DIR/backend-unicode.json" > "$SESSION_DIR/backend-unicode.log" 2>&1
record backend-unicode $?
stop_server unicode

# 2. Import provenance, empty database.
start_server import 18211 "$TMP/import.db" baseline
node "$ROOT/docs/testing/probes/import-provenance.mjs" http://127.0.0.1:18211 "$TMP/import.db" "$SESSION_DIR/backend-import-provenance.json" > "$SESSION_DIR/backend-import-provenance.log" 2>&1
record backend-import-provenance $?
stop_server import

# 3. Contract and validation on a populated database.
start_server contract 18212 "$TMP/contract.db" baseline
seed_sample 18212
node "$ROOT/docs/testing/probes/contract-validation.mjs" http://127.0.0.1:18212 "$SESSION_DIR/backend-contract-validation.json" > "$SESSION_DIR/backend-contract-validation.log" 2>&1
record backend-contract-validation $?
stop_server contract

# 4. Capacity boundary and race for slot 5,000.
start_server capacity 18213 "$TMP/capacity.db" baseline
node "$ROOT/docs/testing/probes/capacity-race.mjs" http://127.0.0.1:18213 "$TMP/capacity.db" "$SESSION_DIR/backend-capacity-race.json" > "$SESSION_DIR/backend-capacity-race.log" 2>&1
record backend-capacity-race $?
stop_server capacity

# 5. Concurrency and snapshot isolation.
start_server concurrency 18214 "$TMP/concurrency.db" baseline
node "$ROOT/docs/testing/probes/concurrency.mjs" http://127.0.0.1:18214 "$TMP/concurrency.db" "$SESSION_DIR/backend-concurrency.json" > "$SESSION_DIR/backend-concurrency.log" 2>&1
record backend-concurrency $?
stop_server concurrency

# 6. Persistence across restart + write-lock failure, same database.
start_server persistence-seed 18215 "$TMP/persistence.db" baseline
node "$ROOT/docs/testing/probes/persistence.mjs" seed http://127.0.0.1:18215 "$SESSION_DIR/backend-persistence-state.json" > "$SESSION_DIR/backend-persistence-seed.log" 2>&1
record backend-persistence-seed $?
stop_server persistence-seed
start_server persistence-verify 18215 "$TMP/persistence.db" baseline
node "$ROOT/docs/testing/probes/persistence.mjs" verify http://127.0.0.1:18215 "$SESSION_DIR/backend-persistence-state.json" "$SESSION_DIR/backend-persistence-restart.json" > "$SESSION_DIR/backend-persistence-verify.log" 2>&1
record backend-persistence-verify $?
node "$ROOT/docs/testing/probes/persistence.mjs" busy http://127.0.0.1:18215 "$TMP/persistence.db" "$SESSION_DIR/backend-persistence-busy.json" > "$SESSION_DIR/backend-persistence-busy.log" 2>&1
record backend-persistence-busy $?
stop_server persistence-verify

# 7. Search baseline + phrases + empty knowledge base.
start_server search-baseline 18216 "$TMP/search-baseline.db" baseline
node "$ROOT/docs/testing/probes/seed-search-corpus.mjs" http://127.0.0.1:18216 > "$SESSION_DIR/seed-search-baseline.log" 2>&1
record seed-search-baseline $?
start_server search-phrases 18217 "$TMP/search-phrases.db" phrases
node "$ROOT/docs/testing/probes/seed-search-corpus.mjs" http://127.0.0.1:18217 > "$SESSION_DIR/seed-search-phrases.log" 2>&1
record seed-search-phrases $?
start_server search-empty 18218 "$TMP/search-empty.db" baseline
node "$ROOT/docs/testing/probes/search-phrases.mjs" http://127.0.0.1:18216 http://127.0.0.1:18217 http://127.0.0.1:18218 "$SESSION_DIR/backend-search-phrases.json" > "$SESSION_DIR/backend-search-phrases.log" 2>&1
record backend-search-phrases $?
stop_server search-empty
stop_server search-phrases
stop_server search-baseline

for db in "$TMP"/*.db; do
  [ -e "$db" ] || continue
  sha256sum "$db" >> "$SESSION_DIR/temp-database-sha256.txt"
done
echo "TMP=$TMP" | tee "$SESSION_DIR/temp-dir.txt"
exit "$STATUS"
