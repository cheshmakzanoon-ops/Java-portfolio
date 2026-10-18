#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
ensure_build
if ! require_running_build >/dev/null 2>&1; then "$PULSE_PROJECT/scripts/up.sh"; fi
require_running_build
PULSE_DEMO_FINGERPRINT=$(fingerprint)
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
sleep 31
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" recover
curl --max-time 15 --fail --silent --show-error -H 'X-Dev-Tenant: t' "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/v1/runs/$PULSE_DEMO_RUN/windows?tenant=t&sensor=a&type=TUMBLING" | python3 -c 'import json,sys; rows=json.load(sys.stdin); assert len(rows)==1 and rows[0]["count"]==3 and rows[0]["sum"]==35 and rows[0]["finalized"] is True, rows; print("HTTP_RESULT_PASS")'
curl --max-time 15 --fail --silent --show-error -H 'X-Dev-Tenant: t' "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/v1/runs/$PULSE_DEMO_RUN/status?tenant=t" | python3 -c 'import json,sys; s=json.load(sys.stdin); assert s["duplicateCount"]==1, s; assert s["checkpoint"], s; print("HTTP_STATUS_PASS")'
set +e
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" outbox-crash
PULSE_CRASH_EXIT=$?
set -e
[[ $PULSE_CRASH_EXIT == 74 ]] || exit 1
sleep 6
java_worker "$PULSE_DEMO_RUN" "$PULSE_DEMO_TOPIC" outbox-retry
echo 'DEMO_PASS: SQL final result, late contribution, dedup, oracle digest, process recovery, outbox retry asserted.'
} 2>&1 | tee "$PULSE_RUNTIME/demo-transcript.log"
[[ "$PULSE_DEMO_FINGERPRINT" == "$(fingerprint)" ]] || { echo 'Inputs changed during demo; result is stale' >&2; exit 1; }
python3 - "$PULSE_PROJECT" "$PULSE_RUNTIME/demo-transcript.log" "$PULSE_DEMO_FINGERPRINT" "$PULSE_DEMO_RUN" <<'PYDEMO'
import datetime,hashlib,json,re,sys
from pathlib import Path
project,log,revision,run=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3],sys.argv[4]
text=log.read_text()
pairs=re.findall(r'RECOVERY_DIGEST=([0-9a-f]{64}) ORACLE_DIGEST=([0-9a-f]{64})',text)
assert len(pairs)==1 and pairs[0][0]==pairs[0][1], 'Missing/mismatched real digests'
assert all(s in text for s in ['HTTP_RESULT_PASS','HTTP_STATUS_PASS','OUTBOX_RETRIED=','DEMO_PASS'])
result={'schemaVersion':1,'project':'pulsestream','sourceRevision':revision,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':'projects/04-pulsestream/scripts/demo.sh','exitCode':0,'run':run,'assertions':{'passed':7,'failed':0},'assertionBoundary':'Seven shell/API barriers; additional live worker invariants and digest equality are executed but not counted as separate methods.','recoveryDigest':pairs[0][0],'oracleDigest':pairs[0][1],'rawArtifact':'.work/A04/runtime/demo-transcript.log','transcriptSha256':hashlib.sha256(log.read_bytes()).hexdigest(),'limitations':['Disposable trusted loopback profile; real protected JWT validation is a separate integration suite.']}
(project/'evidence/demo.json').write_text(json.dumps(result,indent=2)+'\n')
(project/'evidence/demo-output.txt').write_text('\n'.join(line for line in text.splitlines() if any(marker in line for marker in ['PHASE_PASS','TUMBLING=','TUMBLING:','SLIDING:','RECOVERY_DIGEST','HTTP_','OUTBOX_RETRIED','DEMO_PASS']))+'\n')
PYDEMO
