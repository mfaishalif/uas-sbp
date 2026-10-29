#!/usr/bin/env bash
# Active FS-01 reproduction runner for the original 26,000-emoji scenario.
# This keeps the historical probe runnable while writing to a fresh directory.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROBES="$ROOT/docs/testing/probes"
FRONTEND="$ROOT/packages/frontend"
DB="$TMP_DIR/emoji.db"
SEED_CSV="$TMP_DIR/emoji-seed.csv"
API_PORT=8080

write_environment run-emoji-check
require_files "$JAR" "$PROBES/emoji-input-check.mjs" "$FRONTEND/.next/BUILD_ID" || abort
require_free_ports "$API_PORT" 3000 || abort

start_server api "$API_PORT" "$DB" baseline yes "$RESULT_DIR/server-emoji.log" || abort

(
  cd "$ROOT" || exit 1
  FINRESOLVE_API_ORIGIN="http://127.0.0.1:$API_PORT" \
    npm run start --workspace @finresolve/frontend
) > "$RESULT_DIR/frontend-emoji.log" 2>&1 &
WEB_PID=$!
SERVER_PIDS[web]=$WEB_PID
WEB_READY=0
for _ in $(seq 1 240); do
  if curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1; then
    echo "WEB READY pid=$WEB_PID"
    WEB_READY=1
    break
  fi
  if ! kill -0 "$WEB_PID" 2>/dev/null; then
    echo "ERROR web server exited during startup" >&2
    tail -40 "$RESULT_DIR/frontend-emoji.log" >&2 || true
    abort
  fi
  sleep 0.5
done
if [ "$WEB_READY" -ne 1 ]; then
  echo "ERROR web server did not become ready on port 3000" >&2
  tail -40 "$RESULT_DIR/frontend-emoji.log" >&2 || true
  abort
fi

printf 'Complaint ID,Product,Issue,Consumer complaint narrative,Date received\n995000777,Checking or savings account,Managing an account,"A seed narrative for category membership.",2024-11-12\n' > "$SEED_CSV"
SEED_HTTP="$(curl -sS -o "$RESULT_DIR/emoji-seed.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$API_PORT/api/imports/cfpb" \
  -F "file=@$SEED_CSV;type=text/csv" \
  -F 'metadata={"archiveUrl":"https://example.com/emoji.zip","sourceFileName":"emoji-seed.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json')"
echo "seed_http=$SEED_HTTP" | tee "$RESULT_DIR/seed-http.txt"
if ! printf '%s' "$SEED_HTTP" | grep -Eq '^20[01]$'; then
  record seed-emoji 1
  finish
fi
if ! node -e '
  const fs = require("node:fs");
  const report = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  if (!(report.processed > 0) || !(report.imported > 0)) process.exit(1);
' "$RESULT_DIR/emoji-seed.json"; then
  record seed-emoji 1
  finish
fi
record seed-emoji 0

run_command emoji-input "$RESULT_DIR/emoji-input.log" \
  node "$PROBES/emoji-input-check.mjs" http://127.0.0.1:3000 "http://127.0.0.1:$API_PORT" \
  "$RESULT_DIR/screenshots" "$RESULT_DIR/emoji-input.json"

stop_server web
stop_server api
sha256sum "$DB" > "$RESULT_DIR/database-sha256.txt"
finish
