#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
[[ -f "$PULSE_PROJECT/verification/target/pulsestream-verification-1.0.0-SNAPSHOT.jar" ]] || build
compose up -d --wait --wait-timeout 180
if ! curl --fail --silent "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/health" >/dev/null; then "$PULSE_PROJECT/scripts/up.sh"; fi
PULSE_DEMO_RUN="demo_$(date +%s)${BASHPID}"
PULSE_DEMO_TOPIC="pulse-$PULSE_DEMO_RUN"
create_topic "$PULSE_DEMO_TOPIC"
create_topic "$PULSE_DEMO_TOPIC-results"
printf '%s\n' "$PULSE_DEMO_RUN" > "$PULSE_WORK/runtime/last-demo-run"
{
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" seed
set +e
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" late-after
PULSE_CRASH_EXIT=$?
set -e
[[ $PULSE_CRASH_EXIT == 74 ]] || { echo "Expected process crash 74, observed $PULSE_CRASH_EXIT" >&2; exit 1; }
sleep 6
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" recover
curl --fail --silent --show-error -H 'X-Dev-Tenant: t' "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/v1/runs/$PULSE_DEMO_RUN/windows?tenant=t&sensor=a&type=TUMBLING" | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert len(rows)==1 and rows[0]["count"]==3 and rows[0]["sum"]==35 and rows[0]["finalized"] is True, rows; print("HTTP_RESULT_PASS")'
curl --fail --silent --show-error -H 'X-Dev-Tenant: t' "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/v1/runs/$PULSE_DEMO_RUN/status?tenant=t" | python3 -c 'import json,sys; s=json.load(sys.stdin); assert s["duplicateCount"]==1, s; assert s["checkpoint"], s; print("HTTP_STATUS_PASS")' 
set +e
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" outbox-crash
PULSE_CRASH_EXIT=$?
set -e
[[ $PULSE_CRASH_EXIT == 74 ]] || exit 1
sleep 6
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" outbox-retry
echo 'DEMO_PASS: SQL final result, late contribution, dedup, oracle digest, process recovery, outbox retry asserted.'
} 2>&1 | tee "$PULSE_WORK/runtime/demo-transcript.log"
