#!/usr/bin/env bash
# Self-test for the active runner orchestration contract.
#
# Verifies that the runners:
#   * reject an already-used port before launching a server;
#   * propagate a failing command as a non-zero runner exit code;
#   * stop a started backend and free its port during cleanup;
#   * create distinct result directories on separate executions.
#
# Usage: bash docs/testing/runners/self-test.sh [evidence-dir]
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVIDENCE="${1:-$(mktemp -d /tmp/finresolve-runner-self-test-XXXXXX)}"
WORK="$(mktemp -d /tmp/finresolve-runner-work-XXXXXX)"
mkdir -p "$EVIDENCE"
CHECKS=0
FAILURES=0

pass() {
  CHECKS=$((CHECKS + 1))
  echo "PASS $1" | tee -a "$EVIDENCE/self-test.log"
}
fail() {
  CHECKS=$((CHECKS + 1))
  FAILURES=$((FAILURES + 1))
  echo "FAIL $1" | tee -a "$EVIDENCE/self-test.log"
}
cleanup() {
  [ -n "${DUMMY_PID:-}" ] && kill "$DUMMY_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# 1. Used-port rejection through the real regression runner.
node -e '
  const net = require("node:net");
  const server = net.createServer().listen(18220, "127.0.0.1", () => {
    require("node:fs").writeFileSync(process.argv[1], String(process.pid));
  });
  process.on("SIGTERM", () => server.close(() => process.exit(0)));
' "$EVIDENCE/port-holder.pid" &
DUMMY_PID=$!
for _ in $(seq 1 50); do [ -f "$EVIDENCE/port-holder.pid" ] && break; sleep 0.1; done
FINRESOLVE_RESULT_DIR="$EVIDENCE/port-refusal" bash "$SELF_DIR/run-regression.sh" \
  > "$EVIDENCE/port-refusal.log" 2>&1
CODE=$?
if [ "$CODE" -ne 0 ] && grep -q "port 18220 is already in use" "$EVIDENCE/port-refusal.log"; then
  pass "auto-port-refusal exit=$CODE"
else
  fail "auto-port-refusal exit=$CODE"
fi
kill "$DUMMY_PID" 2>/dev/null || true
wait "$DUMMY_PID" 2>/dev/null || true
DUMMY_PID=""

# 2. Failure propagation and process cleanup through the shared orchestration.
cat > "$WORK/failing-runner.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -uo pipefail
source "$RUNNERS/common.sh"
write_environment failing-runner-self-test
start_server self 18401 "$TMP_DIR/self.db" baseline yes "$RESULT_DIR/server.log" || abort
printf '%s\n' "${SERVER_PIDS[self]}" > "$RESULT_DIR/server.pid"
run_command intentional-failure "$RESULT_DIR/intentional-failure.log" bash -c 'exit 7'
finish
SCRIPT
chmod +x "$WORK/failing-runner.sh"
RUNNERS="$SELF_DIR" FINRESOLVE_RESULT_DIR="$EVIDENCE/failure" "$WORK/failing-runner.sh" \
  > "$EVIDENCE/failure.log" 2>&1
CODE=$?
PID="$(cat "$EVIDENCE/failure/server.pid" 2>/dev/null || true)"
if [ "$CODE" -eq 1 ] && [ -n "$PID" ] && ! kill -0 "$PID" 2>/dev/null; then
  pass "failure-propagation-and-cleanup exit=$CODE pid=$PID"
else
  fail "failure-propagation-and-cleanup exit=$CODE pid=$PID"
fi
if ! (exec 3<>/dev/tcp/127.0.0.1/18401) 2>/dev/null; then
  pass "cleanup-freed-port-18401"
else
  fail "cleanup-freed-port-18401"
  exec 3>&- 2>/dev/null || true
fi

# 3. Distinct result directories on two executions with no explicit override.
FIRST="$(env -u FINRESOLVE_RESULT_DIR bash -c 'source "$1/common.sh"; printf "%s" "$RESULT_DIR"' _ "$SELF_DIR")"
SECOND="$(env -u FINRESOLVE_RESULT_DIR bash -c 'source "$1/common.sh"; printf "%s" "$RESULT_DIR"' _ "$SELF_DIR")"
if [ -n "$FIRST" ] && [ -n "$SECOND" ] && [ "$FIRST" != "$SECOND" ]; then
  pass "unique-result-directories first=$FIRST second=$SECOND"
else
  fail "unique-result-directories first=$FIRST second=$SECOND"
fi
rm -rf "$FIRST" "$SECOND"

if [ "$FAILURES" -eq 0 ]; then
  echo "SUMMARY checks=$CHECKS failed=$FAILURES" | tee -a "$EVIDENCE/self-test.log"
  exit 0
fi
echo "SUMMARY checks=$CHECKS failed=$FAILURES" | tee -a "$EVIDENCE/self-test.log"
exit 1
