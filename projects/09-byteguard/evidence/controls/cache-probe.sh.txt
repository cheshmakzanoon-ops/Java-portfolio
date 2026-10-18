#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT=/workspace/Java-portfolio/.work/A09/cache-harness
TASK_REAL=/workspace/Java-portfolio/projects/09-byteguard
mkdir -p "$TASK_ROOT/projects/09-byteguard/scripts" "$TASK_ROOT/projects/09-byteguard/analysis-core/src/main/java" "$TASK_ROOT/projects/09-byteguard/cli/target"
cp "$TASK_REAL/scripts/common.sh" "$TASK_ROOT/projects/09-byteguard/scripts/common.sh"
cat > "$TASK_ROOT/mvnw" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
calls=0
[[ -f .calls ]] && calls=$(cat .calls)
printf '%s\n' "$((calls+1))" > .calls
sha256sum projects/09-byteguard/analysis-core/src/main/java/Minimal.java > projects/09-byteguard/cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar
WRAPPER
chmod +x "$TASK_ROOT/mvnw"
printf 'class Minimal {}\n' > "$TASK_ROOT/projects/09-byteguard/analysis-core/src/main/java/Minimal.java"
source "$TASK_ROOT/projects/09-byteguard/scripts/common.sh"
byteguard_build
[[ $(cat "$TASK_ROOT/.calls") == 1 ]]
byteguard_build
[[ $(cat "$TASK_ROOT/.calls") == 1 ]]
printf '\n' >> "$TASK_ROOT/projects/09-byteguard/analysis-core/src/main/java/Minimal.java"
byteguard_build
[[ $(cat "$TASK_ROOT/.calls") == 2 ]]
printf 'tampered\n' > "$BYTEGUARD_JAR"
byteguard_build
[[ $(cat "$TASK_ROOT/.calls") == 3 ]]
printf 'PASS: first build; identical source/artifact cache hit; actual Java source change rebuild; artifact tamper rebuild.\n'
