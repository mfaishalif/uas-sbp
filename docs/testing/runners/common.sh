#!/usr/bin/env bash
# Shared orchestration for the active FinResolve retest runners.
#
# Design rules:
#   * the repository root is derived from this file, never hard-coded;
#   * every execution writes to a brand-new result directory;
#   * every execution gets a unique temporary SQLite location;
#   * a database is only deleted when the caller explicitly asks for a reset;
#   * each server keeps its own PID, and cleanup runs on success and failure;
#   * used ports are rejected before any server is launched;
#   * command exit codes are sticky and become the runner exit code.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
JAR="$ROOT/backend/target/finresolve-backend-0.1.0.jar"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_DIR="${FINRESOLVE_RESULT_DIR:-$ROOT/docs/testing/results/retest-$STAMP-$$}"
if [ -e "$RESULT_DIR" ]; then
  RESULT_DIR="${RESULT_DIR}-$RANDOM"
fi
TMP_DIR="${FINRESOLVE_TMP_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/finresolve-qa-XXXXXX")}"
STATUS=0
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mkdir -p "$RESULT_DIR"
declare -A SERVER_PIDS=()

kill_tree() {
  local pid=$1
  [ -z "$pid" ] && return 0
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null); do
    kill_tree "$child"
  done
  kill -TERM "$pid" 2>/dev/null || true
}

stop_server() {
  local name=$1
  local pid="${SERVER_PIDS[$name]:-}"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill_tree "$pid"
    for _ in $(seq 1 80); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.25
    done
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    echo "STOP $name pid=$pid"
  fi
  unset 'SERVER_PIDS[$name]'
}

cleanup() {
  local names name
  names="$(printf '%s\n' "${!SERVER_PIDS[@]}")"
  for name in $names; do
    stop_server "$name" >/dev/null 2>&1 || true
  done
  if [ "${FINRESOLVE_KEEP_TMP:-0}" != "1" ] && [ -n "${TMP_DIR:-}" ]; then
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT
trap 'STATUS=130; finish' INT
trap 'STATUS=143; finish' TERM

port_in_use() {
  local port=$1
  if command -v ss >/dev/null 2>&1; then
    ss -ltn "sport = :$port" 2>/dev/null | grep -q "LISTEN"
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
  fi
}

require_free_ports() {
  local port failed=0
  for port in "$@"; do
    if port_in_use "$port"; then
      echo "ERROR port $port is already in use" >&2
      failed=1
    fi
  done
  return "$failed"
}

require_files() {
  local file failed=0
  for file in "$@"; do
    if [ ! -f "$file" ]; then
      echo "ERROR missing required file: $file" >&2
      failed=1
    fi
  done
  return "$failed"
}

record() {
  local label=$1 code=$2
  echo "$label exit=$code" | tee -a "$RESULT_DIR/exit-codes.txt"
  if [ "$code" -ne 0 ]; then
    STATUS=1
  fi
}

run_command() {
  local label=$1 logfile=$2
  shift 2
  "$@" > "$logfile" 2>&1
  local code=$?
  record "$label" "$code"
  return 0
}

start_server() {
  local name=$1 port=$2 db=$3 mode=$4 reset=$5 logfile=$6
  if port_in_use "$port"; then
    echo "ERROR cannot start $name: port $port is in use" >&2
    return 1
  fi
  if [ "$reset" = "yes" ]; then
    rm -f "$db" "$db-wal" "$db-shm"
  elif [ ! -f "$db" ]; then
    echo "ERROR cannot restart $name: database $db does not exist" >&2
    return 1
  fi

  echo "START $name port=$port db=$db mode=$mode reset=$reset"
  (
    cd "$ROOT" || exit 1
    FINRESOLVE_DB_PATH="$db" \
    FINRESOLVE_DEMO_ENABLED=false \
    FINRESOLVE_SIMILARITY_MODE="$mode" \
    SERVER_ADDRESS=127.0.0.1 \
    SERVER_PORT="$port" \
    java -jar "$JAR"
  ) > "$logfile" 2>&1 &
  local pid=$!
  SERVER_PIDS[$name]=$pid

  for _ in $(seq 1 240); do
    if curl -fsS "http://127.0.0.1:$port/api/health" >/dev/null 2>&1; then
      echo "READY $name pid=$pid"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR $name exited during startup" >&2
      tail -40 "$logfile" >&2 || true
      return 1
    fi
    sleep 0.5
  done
  echo "ERROR $name did not become ready on port $port" >&2
  tail -40 "$logfile" >&2 || true
  return 1
}

seed_sample() {
  local port=$1 out=$2
  local http
  http="$(curl -sS -o "$out" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$port/api/imports/cfpb" \
    -F "file=@$ROOT/data/cfpb/sample.csv;type=text/csv" \
    -F 'metadata={"archiveUrl":"https://files.consumerfinance.gov/f/documents/CCDB_Export_8_November_2024_through_December_2024.zip","sourceFileName":"finresolve-sample.csv","periodStart":"2024-11-01","periodEnd":"2024-12-31"};type=application/json')"
  echo "seed_sample http=$http" >> "$RESULT_DIR/seed-http.txt"
  if ! printf '%s' "$http" | grep -Eq '^20[01]$'; then
    echo "ERROR sample seed returned HTTP $http" >&2
    return 1
  fi
  if ! node -e '
    const fs = require("node:fs");
    const report = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (!(report.processed > 0) || !((report.imported ?? 0) + (report.duplicates ?? 0) > 0)) {
      console.error("seed report is empty or inconsistent", report);
      process.exit(1);
    }
  ' "$out"; then
    echo "ERROR sample seed report validation failed" >&2
    return 1
  fi
  echo "SEED sample ok processed=$(node -e 'console.log(JSON.parse(require("node:fs").readFileSync(process.argv[1],"utf8")).processed)' "$out")"
}

write_environment() {
  local runner=$1
  {
    echo "runner=$runner"
    echo "startedAt=$STARTED_AT"
    echo "root=$ROOT"
    echo "resultDir=$RESULT_DIR"
    echo "tempDir=$TMP_DIR"
    echo "commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "gitStatus=$(git -C "$ROOT" status --porcelain | wc -l) changed entries"
    echo "node=$(node --version 2>/dev/null || echo unknown)"
    echo "npm=$(npm --version 2>/dev/null || echo unknown)"
    echo "java=$(java -version 2>&1 | head -1)"
    echo "jar=$JAR"
    echo "jarSha256=$(sha256sum "$JAR" 2>/dev/null | cut -d' ' -f1 || echo missing)"
    echo "sampleSha256=$(sha256sum "$ROOT/data/cfpb/sample.csv" 2>/dev/null | cut -d' ' -f1 || echo missing)"
  } > "$RESULT_DIR/environment.txt"
}

finish() {
  echo "finishedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$RESULT_DIR/environment.txt"
  echo "status=$STATUS" >> "$RESULT_DIR/environment.txt"
  echo "RESULT_DIR=$RESULT_DIR"
  printf 'STATUS=%s\n' "$STATUS" | tee -a "$RESULT_DIR/exit-codes.txt"
  exit "$STATUS"
}

# Abort on an orchestration failure (missing file, used port, server startup)
# while still returning a failing exit code.
abort() {
  STATUS=1
  finish
}
