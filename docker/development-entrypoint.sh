#!/bin/sh
set -u

cd /workspace/backend

maven_pid=
terminate() {
    if [ -n "$maven_pid" ]; then
        kill -TERM "$maven_pid" 2>/dev/null || true
        wait "$maven_pid" 2>/dev/null || true
    fi
    exit 143
}
trap terminate TERM INT

./mvnw -B -DskipTests clean spring-boot:run &
maven_pid=$!
wait "$maven_pid"
status=$?
maven_pid=

if [ "$status" -eq 0 ]; then
    exit 0
fi

echo "Development build or startup failed (exit $status)." >&2
echo "Waiting for the next source change to compile and start the backend again." >&2
exec sleep infinity
