#!/usr/bin/env bash
# Reproduce deliberate gate failures using an isolated, disposable fixture.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
if [[ -d /workspace/.tools/jdk-25 ]]; then source scripts/cloud-env.sh; fi
mkdir -p .work
fixture=$(mktemp -d "$root/.work/foundation-probe.XXXXXX")
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/src/main/java/foundation" "$fixture/src/test/java/foundation"
cat > "$fixture/pom.xml" <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>java-portfolio</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../../pom.xml</relativePath></parent><artifactId>foundation-probe</artifactId><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies><build><plugins><plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><executions><execution><id>coverage-gate</id><phase>verify</phase><goals><goal>check</goal></goals><configuration><rules><rule><element>BUNDLE</element><limits><limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.85</minimum></limit><limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.75</minimum></limit></limits></rule></rules></configuration></execution></executions></plugin></plugins></build></project>
XML
cat > "$fixture/src/main/java/foundation/Probe.java" <<'JAVA'
package foundation;
public final class Probe {
  public static int absolute(int value) { return value < 0 ? -value : value; }
}
JAVA
cat > "$fixture/src/test/java/foundation/ProbeTest.java" <<'JAVA'
package foundation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class ProbeTest {
  @Test void branches() {
    new Probe();
    assertEquals(2, Probe.absolute(-2));
    assertEquals(2, Probe.absolute(2));
  }
}
JAVA
logs="$root/.work/foundation-gates"
mkdir -p "$logs"
run() { "$root/mvnw" -f "$fixture/pom.xml" -Dmaven.repo.local="$root/.work/coordinator/m2" -B -ntp "$@"; }
run com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify > "$logs/baseline.log" 2>&1
cp "$fixture/src/test/java/foundation/ProbeTest.java" "$fixture/good-test"
sed 's/assertEquals(2, Probe.absolute(2))/assertEquals(999, Probe.absolute(2))/' "$fixture/good-test" > "$fixture/src/test/java/foundation/ProbeTest.java"
if run test > "$logs/test-failure.log" 2>&1; then echo 'Broken assertion unexpectedly passed' >&2; exit 1; fi
grep -q 'Failures: 1' "$logs/test-failure.log"
cp "$fixture/good-test" "$fixture/src/test/java/foundation/ProbeTest.java"
printf '\n//bad spacing\n' >> "$fixture/src/main/java/foundation/Probe.java"
if run com.diffplug.spotless:spotless-maven-plugin:3.0.0:check > "$logs/format-failure.log" 2>&1; then echo 'Broken format unexpectedly passed' >&2; exit 1; fi
grep -q 'format violations' "$logs/format-failure.log"
run com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply > "$logs/restore-format.log" 2>&1
sed '/new Probe();/d' "$fixture/good-test" > "$fixture/src/test/java/foundation/ProbeTest.java"
if run clean verify > "$logs/coverage-failure.log" 2>&1; then echo 'Low coverage unexpectedly passed' >&2; exit 1; fi
grep -q 'Coverage checks have not been met' "$logs/coverage-failure.log"
cp "$fixture/good-test" "$fixture/src/test/java/foundation/ProbeTest.java"
run com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply clean verify > "$logs/final.log" 2>&1
printf 'Foundation baseline and restored checks PASS; deliberate test, format and coverage failures observed. Logs: %s\n' "$logs"
