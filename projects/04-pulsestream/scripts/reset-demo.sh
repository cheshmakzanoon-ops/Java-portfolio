#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
[[ -f "$PULSE_WORK/runtime/last-demo-run" ]] || { echo 'No seeded demo recorded'; exit 0; }
PULSE_DEMO_RUN=$(cat "$PULSE_WORK/runtime/last-demo-run")
[[ "$PULSE_DEMO_RUN" =~ ^demo_[0-9]+$ ]] || { echo 'Invalid demo scope' >&2; exit 1; }
compose exec -T postgres psql -U pulse -d pulse -v ON_ERROR_STOP=1 -v "run=$PULSE_DEMO_RUN" <<'SQL'
BEGIN;
DELETE FROM outbox WHERE run_id=:'run';
DELETE FROM late_record WHERE run_id=:'run';
DELETE FROM quarantine WHERE run_id=:'run';
DELETE FROM seen_event WHERE run_id=:'run';
DELETE FROM window_result WHERE run_id=:'run';
DELETE FROM pane_state WHERE run_id=:'run';
DELETE FROM checkpoint WHERE run_id=:'run';
DELETE FROM partition_progress WHERE run_id=:'run';
DELETE FROM run_state WHERE run_id=:'run';
DELETE FROM run_usage WHERE run_id=:'run';
DELETE FROM run_owner WHERE run_id=:'run';
COMMIT;
SQL
for PULSE_TOPIC in "pulse-$PULSE_DEMO_RUN" "pulse-$PULSE_DEMO_RUN-results"; do compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic "$PULSE_TOPIC"; done
rm -f "$PULSE_WORK/runtime/last-demo-run"
