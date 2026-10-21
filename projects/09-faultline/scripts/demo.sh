#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
faultline_build
mkdir -p "$FAULTLINE_RUNTIME/demo/positive" "$FAULTLINE_RUNTIME/demo/corrected"
cat > "$FAULTLINE_RUNTIME/demo/positive/Demo.java" <<'JAVA'
public class Demo {
 static { if(Boolean.getBoolean("faultline.runSentinel")) throw new AssertionError("Target initialized"); }
 static String external(){return System.getenv("FAULTLINE_DEMO");}
 static void query(java.sql.Statement stmt,String input)throws Exception{stmt.execute("select * from users where name='"+input+"'");}
 public static void run(java.sql.Statement stmt)throws Exception{query(stmt,external());}
}
JAVA
cat > "$FAULTLINE_RUNTIME/demo/corrected/Demo.java" <<'JAVA'
public class Demo {
 static String external(){return System.getenv("FAULTLINE_DEMO");}
 public static void run(java.sql.Connection conn)throws Exception{java.sql.PreparedStatement stmt=conn.prepareStatement("select * from users where name=?");stmt.setString(1,external());stmt.execute();}
}
JAVA
for variant in positive corrected; do
 javac -proc:none -g --release 25 -d "$FAULTLINE_RUNTIME/demo/$variant" "$FAULTLINE_RUNTIME/demo/$variant/Demo.java"
 jar --create --file "$FAULTLINE_RUNTIME/demo/$variant.jar" -C "$FAULTLINE_RUNTIME/demo/$variant" Demo.class
done
faultline_scan_expect 1 --input "$FAULTLINE_RUNTIME/demo/positive.jar" --rules "$FAULTLINE_PROJECT/rules/core.yaml" --out "$FAULTLINE_RUNTIME/demo/positive.sarif"
faultline_scan_expect 0 --input "$FAULTLINE_RUNTIME/demo/corrected.jar" --rules "$FAULTLINE_PROJECT/rules/core.yaml" --out "$FAULTLINE_RUNTIME/demo/corrected.sarif"
faultline_scan_expect 3 --input "$FAULTLINE_RUNTIME/demo/positive.jar" --work-budget 1 --out "$FAULTLINE_RUNTIME/demo/partial.sarif"
faultline_scan_expect 0 --input "$FAULTLINE_RUNTIME/demo/positive.jar" --baseline "$FAULTLINE_RUNTIME/demo/positive.sarif" --out "$FAULTLINE_RUNTIME/demo/baseline.sarif"
python3 - "$FAULTLINE_RUNTIME/demo" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]);positive=json.loads((root/'positive.sarif').read_text())['runs'][0]
assert len(positive['results'])==1
finding=positive['results'][0]
assert finding['ruleId']=='BG-SQL-TEXT'
assert len(finding['codeFlows'][0]['threadFlows'][0]['locations'])>=3
assert not json.loads((root/'corrected.sarif').read_text())['runs'][0]['results']
partial=json.loads((root/'partial.sarif').read_text())['runs'][0]
assert partial['properties']['partial'] and partial['properties']['unfinishedFrontier']
assert json.loads((root/'baseline.sarif').read_text())['runs'][0]['results'][0]['baselineState']=='unchanged'
print('Demo assertions passed: cross-method finding, corrected binding, partial frontier, exact baseline.')
PY
