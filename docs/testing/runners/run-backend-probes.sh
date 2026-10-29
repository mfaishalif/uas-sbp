#!/usr/bin/env bash
# Active backend probe runner.
#
# Writes a fresh evidence directory per execution and never touches the archived
# 2026-10-28 session. Persistence verification restarts the server on the SAME
# database; only the seed phase is allowed to reset it.
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROBES="$ROOT/docs/testing/probes"
DB_UNICODE="$TMP_DIR/unicode.db"
DB_IMPORT="$TMP_DIR/import.db"
DB_CONTRACT="$TMP_DIR/contract.db"
DB_CAPACITY="$TMP_DIR/capacity.db"
DB_CONCURRENCY="$TMP_DIR/concurrency.db"
DB_PERSISTENCE="$TMP_DIR/persistence.db"
DB_SEARCH_BASELINE="$TMP_DIR/search-baseline.db"
DB_SEARCH_PHRASES="$TMP_DIR/search-phrases.db"
DB_SEARCH_EMPTY="$TMP_DIR/search-empty.db"

write_environment run-backend-probes
require_files "$JAR" "$PROBES/unicode-integrity.mjs" "$PROBES/import-provenance.mjs" \
  "$PROBES/contract-validation.mjs" "$PROBES/capacity-race.mjs" \
  "$PROBES/concurrency.mjs" "$PROBES/persistence.mjs" \
  "$PROBES/seed-search-corpus.mjs" "$PROBES/search-phrases.mjs" || abort
require_free_ports 18210 18211 18212 18213 18214 18215 18216 18217 18218 || abort

# 1. Unicode integrity on an empty database.
start_server unicode 18210 "$DB_UNICODE" baseline yes "$RESULT_DIR/server-unicode.log" || abort
run_command backend-unicode "$RESULT_DIR/backend-unicode.log" \
  node "$PROBES/unicode-integrity.mjs" http://127.0.0.1:18210 "$DB_UNICODE" \
  "$RESULT_DIR/backend-unicode.json"
stop_server unicode

# 2. Import provenance on an empty database.
start_server import 18211 "$DB_IMPORT" baseline yes "$RESULT_DIR/server-import.log" || abort
run_command backend-import-provenance "$RESULT_DIR/backend-import-provenance.log" \
  node "$PROBES/import-provenance.mjs" http://127.0.0.1:18211 "$DB_IMPORT" \
  "$RESULT_DIR/backend-import-provenance.json"
stop_server import

# 3. Contract and validation on a populated database.
start_server contract 18212 "$DB_CONTRACT" baseline yes "$RESULT_DIR/server-contract.log" || abort
seed_sample 18212 "$RESULT_DIR/seed-sample-18212.json" || { record seed-sample 1; finish; }
run_command backend-contract-validation "$RESULT_DIR/backend-contract-validation.log" \
  node "$PROBES/contract-validation.mjs" http://127.0.0.1:18212 \
  "$RESULT_DIR/backend-contract-validation.json"
stop_server contract

# 4. Capacity boundary and race for slot 5,000.
start_server capacity 18213 "$DB_CAPACITY" baseline yes "$RESULT_DIR/server-capacity.log" || abort
run_command backend-capacity-race "$RESULT_DIR/backend-capacity-race.log" \
  node "$PROBES/capacity-race.mjs" http://127.0.0.1:18213 "$DB_CAPACITY" \
  "$RESULT_DIR/backend-capacity-race.json"
stop_server capacity

# 5. Concurrency and snapshot isolation.
start_server concurrency 18214 "$DB_CONCURRENCY" baseline yes "$RESULT_DIR/server-concurrency.log" || abort
run_command backend-concurrency "$RESULT_DIR/backend-concurrency.log" \
  node "$PROBES/concurrency.mjs" http://127.0.0.1:18214 "$DB_CONCURRENCY" \
  "$RESULT_DIR/backend-concurrency.json"
stop_server concurrency

# 6. Persistence across restart + write lock on the same database.
start_server persistence-seed 18215 "$DB_PERSISTENCE" baseline yes "$RESULT_DIR/server-persistence-seed.log" || abort
run_command backend-persistence-seed "$RESULT_DIR/backend-persistence-seed.log" \
  node "$PROBES/persistence.mjs" seed http://127.0.0.1:18215 \
  "$RESULT_DIR/backend-persistence-state.json"
stop_server persistence-seed
start_server persistence-verify 18215 "$DB_PERSISTENCE" baseline no "$RESULT_DIR/server-persistence-verify.log" || abort
run_command backend-persistence-verify "$RESULT_DIR/backend-persistence-verify.log" \
  node "$PROBES/persistence.mjs" verify http://127.0.0.1:18215 \
  "$RESULT_DIR/backend-persistence-state.json" "$RESULT_DIR/backend-persistence-restart.json"
run_command backend-persistence-busy "$RESULT_DIR/backend-persistence-busy.log" \
  node "$PROBES/persistence.mjs" busy http://127.0.0.1:18215 "$DB_PERSISTENCE" \
  "$RESULT_DIR/backend-persistence-busy.json"
stop_server persistence-verify

# 7. Search baseline, phrases, and empty knowledge base.
start_server search-baseline 18216 "$DB_SEARCH_BASELINE" baseline yes "$RESULT_DIR/server-search-baseline.log" || abort
run_command seed-search-baseline "$RESULT_DIR/seed-search-baseline.log" \
  node "$PROBES/seed-search-corpus.mjs" http://127.0.0.1:18216
start_server search-phrases 18217 "$DB_SEARCH_PHRASES" phrases yes "$RESULT_DIR/server-search-phrases.log" || abort
run_command seed-search-phrases "$RESULT_DIR/seed-search-phrases.log" \
  node "$PROBES/seed-search-corpus.mjs" http://127.0.0.1:18217
start_server search-empty 18218 "$DB_SEARCH_EMPTY" baseline yes "$RESULT_DIR/server-search-empty.log" || abort
run_command backend-search-phrases "$RESULT_DIR/backend-search-phrases.log" \
  node "$PROBES/search-phrases.mjs" http://127.0.0.1:18216 http://127.0.0.1:18217 \
  http://127.0.0.1:18218 "$RESULT_DIR/backend-search-phrases.json"
stop_server search-empty
stop_server search-phrases
stop_server search-baseline

for db in "$TMP_DIR"/*.db; do
  [ -e "$db" ] || continue
  sha256sum "$db" >> "$RESULT_DIR/database-sha256.txt"
done
echo "TMP=$TMP_DIR" | tee "$RESULT_DIR/temp-dir.txt"
finish
