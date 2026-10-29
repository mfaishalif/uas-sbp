#!/usr/bin/env bash
# Active 140-scenario backend regression runner.
#
# A fresh database is mandatory: tools/exploratory-backend.mjs refuses to run
# against a populated database. The output directory is unique per execution.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

REGRESSION="$ROOT/tools/exploratory-backend.mjs"
DB="$TMP_DIR/regression.db"
PORT=18220

write_environment run-regression
require_files "$JAR" "$REGRESSION" || abort
require_free_ports "$PORT" || abort

start_server regression "$PORT" "$DB" baseline yes "$RESULT_DIR/server-regression.log" || abort

TOTAL="$(curl -fsS "http://127.0.0.1:$PORT/api/cases" | node -e '
  let text = "";
  process.stdin.on("data", (chunk) => { text += chunk; });
  process.stdin.on("end", () => {
    const body = JSON.parse(text);
    process.stdout.write(String(body.totalElements));
  });
')"
if [ "$TOTAL" != "0" ]; then
  echo "ERROR regression database is not empty (totalElements=$TOTAL)" >&2
  record seed-regression 1
  finish
fi
record seed-regression 0

run_command regression "$RESULT_DIR/regression.log" \
  node "$REGRESSION" "http://127.0.0.1:$PORT" "$RESULT_DIR/regression.json"

if [ -f "$RESULT_DIR/regression.json" ]; then
  if ! node -e '
    const fs = require("node:fs");
    const summary = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    if (!(summary.checks === 140 && summary.passed === 140 && summary.failed === 0)) {
      console.error("unexpected regression summary", {
        checks: summary.checks, passed: summary.passed, failed: summary.failed,
      });
      process.exit(1);
    }
  ' "$RESULT_DIR/regression.json"; then
    record regression-summary 1
  else
    record regression-summary 0
  fi
else
  record regression-summary 1
fi

stop_server regression
sha256sum "$DB" > "$RESULT_DIR/database-sha256.txt"
finish
