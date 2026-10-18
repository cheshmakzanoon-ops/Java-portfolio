#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
if [[ -d /workspace/.tools/jdk-25 && -f "$root/.work/settings.xml" ]]; then source "$root/scripts/cloud-env.sh"; fi
command=${1:-help}
slug=${2:-}
slugs=(clearledger durableflow quorumkv pulsestream tenantshield matchcore indexforge threadlens byteguard routeweaver)
resolve() {
  local i
  for i in "${!slugs[@]}"; do
    if [[ ${slugs[$i]} == "$1" ]]; then printf '%s/projects/%02d-%s' "$root" "$((i+1))" "$1"; return; fi
  done
  echo "Unknown project: $1" >&2; return 2
}
case "$command" in
  doctor)
    java -version; javac -version; "$root/mvnw" -version
    if command -v docker >/dev/null; then docker info --format '{{.ServerVersion}}'; else echo 'Docker missing: integration workflow unavailable' >&2; exit 1; fi
    ;;
  list)
    for slug in "${slugs[@]}"; do printf '%-14s %s\n' "$slug" "$(resolve "$slug")"; done
    echo 'ThreadLens and ByteGuard are CLI tools; up does not start a web server.'
    ;;
  verify|integration)
    export PORTFOLIO_TEST_RUN_STARTED=$(date +%s)
    args=(); [[ $command != integration ]] || args+=(-Pintegration)
    mkdir -p "$root/.work/coordinator"
    if [[ $slug == all ]]; then
      "$root/mvnw" -Dmaven.repo.local="$root/.work/coordinator/m2" -B -ntp "${args[@]}" verify
      for selected in "${slugs[@]}"; do python3 "$root/scripts/check-tests.py" "$(resolve "$selected")" "$command"; done
    else
      path=$(resolve "$slug")
      "$root/mvnw" -f "$path/pom.xml" -Dmaven.repo.local="$root/.work/coordinator/m2" -B -ntp "${args[@]}" verify
      python3 "$root/scripts/check-tests.py" "$path" "$command"
    fi
    ;;
  up|demo|down|reset-demo)
    path=$(resolve "$slug"); script="$path/scripts/$command.sh"
    if [[ ! -f $script ]]; then echo "Project command is not implemented: $script" >&2; exit 2; fi
    if command -v timeout >/dev/null; then timeout 600 bash "$script"; else bash "$script"; fi
    ;;
  *) echo 'Usage: portfolio.sh doctor|list|verify|integration|up|demo|down|reset-demo [slug|all]' >&2; exit 2;;
esac
