#!/usr/bin/env bash
# Active browser-session runner.
#
# Evidence is written to a fresh directory per execution. The restart and
# phrases phases reuse the same SQLite database and never reset it.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROBES="$ROOT/docs/testing/probes"
FRONTEND="$ROOT/packages/frontend"
DB="$TMP_DIR/browser.db"

write_environment run-browser-session
require_files "$JAR" "$PROBES/browser-exploratory.mjs" \
  "$PROBES/browser-restart-verify.mjs" "$PROBES/browser-phrases-check.mjs" \
  "$FRONTEND/.next/BUILD_ID" || abort
require_free_ports 8080 3000 || abort

start_api() {
  local mode=$1 reset=$2 logfile=$3
  start_server api 8080 "$DB" "$mode" "$reset" "$logfile"
}

start_web() {
  if port_in_use 3000; then
    echo "ERROR cannot start web: port 3000 is in use" >&2
    return 1
  fi
  (
    cd "$ROOT" || exit 1
    FINRESOLVE_API_ORIGIN=http://127.0.0.1:8080 \
      npm run start --workspace @finresolve/frontend
  ) > "$RESULT_DIR/frontend-browser.log" 2>&1 &
  local pid=$!
  SERVER_PIDS[web]=$pid
  for _ in $(seq 1 240); do
    if curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1; then
      echo "WEB READY pid=$pid"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR web server exited during startup" >&2
      tail -40 "$RESULT_DIR/frontend-browser.log" >&2 || true
      return 1
    fi
    sleep 0.5
  done
  echo "ERROR web server did not become ready on port 3000" >&2
  tail -40 "$RESULT_DIR/frontend-browser.log" >&2 || true
  return 1
}

start_api baseline yes "$RESULT_DIR/server-browser-baseline.log" || abort
start_web || abort

run_command browser-exploratory "$RESULT_DIR/browser-exploratory.log" \
  node "$PROBES/browser-exploratory.mjs" http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$RESULT_DIR/screenshots" "$RESULT_DIR/browser-exploratory.json"
sha256sum "$DB" > "$RESULT_DIR/browser-database-before-restart.sha256"

# Restart on the same database. reset=no is the regression guard for the
# original runner bug that deleted the database before verification.
stop_server api
start_api baseline no "$RESULT_DIR/server-browser-restart.log" || abort
run_command browser-restart "$RESULT_DIR/browser-restart.log" \
  node "$PROBES/browser-restart-verify.mjs" http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$RESULT_DIR/screenshots/browser-restart-state.json" "$RESULT_DIR/screenshots" \
  "$RESULT_DIR/browser-restart.json"

# Phrases mode also reuses the same database.
stop_server api
start_api phrases no "$RESULT_DIR/server-browser-phrases.log" || abort
run_command browser-phrases "$RESULT_DIR/browser-phrases.log" \
  node "$PROBES/browser-phrases-check.mjs" http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$RESULT_DIR/screenshots" "$RESULT_DIR/browser-phrases.json"

stop_server web
stop_server api
sha256sum "$DB" > "$RESULT_DIR/browser-database-after-phrases.sha256"
echo "DB=$DB" | tee "$RESULT_DIR/browser-db.txt"
finish
