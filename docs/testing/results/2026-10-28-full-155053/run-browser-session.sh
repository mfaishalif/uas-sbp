#!/usr/bin/env bash
# Launches a disposable production deployment (JAR + Next.js) for the browser
# session, then restarts the same database to verify persistence and phrases
# mode. No repository database is touched.
set -uo pipefail
ROOT=/workspaces/uas-sbp
S="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/backend/target/finresolve-backend-0.1.0.jar"
DB=/tmp/finresolve-browser-qa.db
API_PID=""
WEB_PID=""
STATUS=0

kill_tree() {
  local pid=$1
  [ -z "$pid" ] && return 0
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill -TERM "$pid" 2>/dev/null || true
}

cleanup() {
  kill_tree "$WEB_PID"
  kill_tree "$API_PID"
}
trap cleanup EXIT

start_api() {
  local mode=$1 log=$2
  (
    cd "$ROOT"
    FINRESOLVE_DB_PATH="$DB" FINRESOLVE_DEMO_ENABLED=false \
    FINRESOLVE_SIMILARITY_MODE="$mode" SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080 \
    java -jar "$JAR"
  ) > "$S/$log" 2>&1 &
  API_PID=$!
  for _ in $(seq 1 240); do
    curl -fsS http://127.0.0.1:8080/api/health >/dev/null 2>&1 && { echo "API READY mode=$mode pid=$API_PID"; return 0; }
    kill -0 "$API_PID" 2>/dev/null || { echo "API EXITED"; tail -40 "$S/$log"; return 1; }
    sleep 0.5
  done
  return 1
}
stop_api() {
  if [ -n "$API_PID" ] && kill -0 "$API_PID" 2>/dev/null; then
    kill_tree "$API_PID"
    for _ in $(seq 1 40); do kill -0 "$API_PID" 2>/dev/null || break; sleep 0.25; done
    kill -KILL "$API_PID" 2>/dev/null || true
    wait "$API_PID" 2>/dev/null || true
    echo "API STOPPED"
  fi
  API_PID=""
}
stop_web() {
  if [ -n "$WEB_PID" ] && kill -0 "$WEB_PID" 2>/dev/null; then
    kill_tree "$WEB_PID"
    for _ in $(seq 1 40); do kill -0 "$WEB_PID" 2>/dev/null || break; sleep 0.25; done
    kill -KILL "$WEB_PID" 2>/dev/null || true
    wait "$WEB_PID" 2>/dev/null || true
    echo "WEB STOPPED"
  fi
  WEB_PID=""
}

rm -f "$DB" "$DB-wal" "$DB-shm"
start_api baseline server-browser-baseline.log || exit 1
(
  cd "$ROOT"
  FINRESOLVE_API_ORIGIN=http://127.0.0.1:8080 npm run start --workspace @finresolve/frontend
) > "$S/frontend-browser.log" 2>&1 &
WEB_PID=$!
for _ in $(seq 1 120); do
  curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1 && { echo "WEB READY pid=$WEB_PID"; break; }
  kill -0 "$WEB_PID" 2>/dev/null || { echo "WEB EXITED"; tail -40 "$S/frontend-browser.log"; exit 1; }
  sleep 0.5
done

node "$ROOT/docs/testing/probes/browser-exploratory.mjs" \
  http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$S/screenshots" "$S/browser-exploratory.json" > "$S/browser-exploratory.log" 2>&1
code=$?; echo "browser-exploratory exit=$code" | tee "$S/browser-exit-codes.txt"
[ "$code" -ne 0 ] && STATUS=1
sha256sum "$DB" >> "$S/browser-exit-codes.txt"

stop_api
start_api baseline server-browser-restart.log || exit 1
node "$ROOT/docs/testing/probes/browser-restart-verify.mjs" \
  http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$S/screenshots/browser-restart-state.json" "$S/screenshots" "$S/browser-restart.json" \
  > "$S/browser-restart.log" 2>&1
code=$?; echo "browser-restart exit=$code" | tee -a "$S/browser-exit-codes.txt"
[ "$code" -ne 0 ] && STATUS=1

stop_api
start_api phrases server-browser-phrases.log || exit 1
node "$ROOT/docs/testing/probes/browser-phrases-check.mjs" \
  http://127.0.0.1:3000 http://127.0.0.1:8080 \
  "$S/screenshots" "$S/browser-phrases.json" > "$S/browser-phrases.log" 2>&1
code=$?; echo "browser-phrases exit=$code" | tee -a "$S/browser-exit-codes.txt"
[ "$code" -ne 0 ] && STATUS=1

stop_web
stop_api
sha256sum "$DB" >> "$S/browser-exit-codes.txt"
echo "DB=$DB" | tee "$S/browser-db.txt"
exit "$STATUS"
