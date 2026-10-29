#!/usr/bin/env bash
# Reproduces the frontend maxlength/code-point boundary check.
# Uses fresh ports and a disposable SQLite database; never touches backend/data.
set -uo pipefail
ROOT=/workspaces/uas-sbp
S="$(cd "$(dirname "$0")" && pwd)"
DB=/tmp/finresolve-emoji-qa.db
API_PID=""
WEB_PID=""
kill_tree() {
  local pid=$1
  [ -z "$pid" ] && return 0
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill -TERM "$pid" 2>/dev/null || true
}
cleanup() {
  kill_tree "$WEB_PID"
  kill_tree "$API_PID"
  sleep 2
  [ -n "$WEB_PID" ] && kill -KILL "$WEB_PID" 2>/dev/null || true
  [ -n "$API_PID" ] && kill -KILL "$API_PID" 2>/dev/null || true
}
trap cleanup EXIT

rm -f "$DB" "$DB-wal" "$DB-shm"
(
  cd "$ROOT"
  FINRESOLVE_DB_PATH="$DB" FINRESOLVE_DEMO_ENABLED=false \
  FINRESOLVE_SIMILARITY_MODE=baseline SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080 \
  java -jar backend/target/finresolve-backend-0.1.0.jar
) > "$S/server-emoji.log" 2>&1 &
API_PID=$!
for _ in $(seq 1 240); do curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1 && break; sleep 0.5; done

(
  cd "$ROOT"
  FINRESOLVE_API_ORIGIN=http://127.0.0.1:8080 npm run start --workspace @finresolve/frontend
) > "$S/frontend-emoji.log" 2>&1 &
WEB_PID=$!
for _ in $(seq 1 120); do curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1 && break; sleep 0.5; done

printf 'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n995000777,Checking or savings account,Managing an account,"A seed narrative for category membership.",2024-11-12\n' > /tmp/emoji-seed.csv
curl -sS -o /tmp/emoji-seed.json -w 'seed_http=%{http_code}\n' \
  -X POST http://127.0.0.1:8080/api/imports/cfpb \
  -F 'file=@/tmp/emoji-seed.csv;type=text/csv' \
  -F 'metadata={"archiveUrl":"https://example.com/emoji.zip","sourceFileName":"emoji-seed.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json'

node "$ROOT/docs/testing/probes/emoji-input-check.mjs" \
  http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$S/screenshots" "$S/emoji-input.json" > "$S/emoji-input.log" 2>&1
code=$?
echo "emoji_exit=$code"
cat "$S/emoji-input.log"
sha256sum "$DB" >> "$S/emoji-input.log"
exit "$code"
