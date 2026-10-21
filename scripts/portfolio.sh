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
verify_prepared_project() {
  case "$1" in
    indexforge) bash "$(resolve "$1")/scripts/verify.sh" ;;
    routeweaver)
      (
        source "$(resolve "$1")/scripts/common.sh"
        before=$(fingerprint)
        "$root/mvnw" -f "$project/pom.xml" -Dmaven.repo.local="$root/.work/build/m2" -B -ntp verify
        [[ "$before" == "$(fingerprint)" ]] || { echo 'RouteWeaver inputs changed during verification' >&2; exit 1; }
        printf '%s\n' "$before" > "$runtime/build-stamp"
      )
      ;;
    *) return 2 ;;
  esac
}
case "$command" in
  doctor)
    if [[ -n $slug ]]; then resolve "$slug" >/dev/null; fi
    java -version; javac -version; "$root/mvnw" -version
    java_spec=$(java -XshowSettings:properties -version 2>&1 |
      awk '/java.specification.version =/ { print $3; exit }')
    javac_version=$(javac -version 2>&1)
    if [[ $java_spec != 25 || ! $javac_version =~ ^javac[[:space:]]25([.]|$) ]]; then
      echo "JDK 25 is required (java.specification.version=$java_spec; $javac_version)" >&2
      exit 1
    fi
    if [[ $slug == matchcore || $slug == threadlens || $slug == byteguard ]]; then
      echo "Docker not required for $slug's CLI demonstration"
    elif command -v docker >/dev/null; then
      docker info --format '{{.ServerVersion}}'
    else
      echo 'Docker missing: service/integration workflows unavailable' >&2
      exit 1
    fi
    ;;
  list)
    for slug in "${slugs[@]}"; do printf '%-14s %s\n' "$slug" "$(resolve "$slug")"; done
    echo 'MatchCore, ThreadLens and ByteGuard are CLI-first; up prepares local state rather than starting a web server.'
    ;;
  verify|integration|mutation)
    export PORTFOLIO_TEST_RUN_STARTED=$(python3 -c 'import time; print(time.time())')
    args=(); [[ $command != integration ]] || args+=(-Pintegration)
    [[ $command != mutation ]] || args+=(-Pmutation -DoutputFormats=XML,HTML)
    mkdir -p "$root/.work/build"
    if [[ $slug == all ]]; then
      "$root/mvnw" -Dmaven.repo.local="$root/.work/build/m2" -B -ntp "${args[@]}" verify
      if [[ $command == verify ]]; then
        # These projects create source-bound runtime receipts only after their
        # supported verification entrypoints complete successfully.
        verify_prepared_project indexforge
        verify_prepared_project routeweaver
      fi
      for selected in "${slugs[@]}"; do python3 "$root/scripts/check-tests.py" "$(resolve "$selected")" "$command"; if [[ $command == mutation ]]; then python3 "$root/scripts/check-mutations.py" "$(resolve "$selected")"; fi; done
    else
      path=$(resolve "$slug")
      if [[ $command == verify && ( $slug == indexforge || $slug == routeweaver ) ]]; then
        verify_prepared_project "$slug"
      else
        "$root/mvnw" -f "$path/pom.xml" -Dmaven.repo.local="$root/.work/build/m2" -B -ntp "${args[@]}" verify
      fi
      python3 "$root/scripts/check-tests.py" "$path" "$command"
      if [[ $command == mutation ]]; then python3 "$root/scripts/check-mutations.py" "$path"; fi
    fi
    ;;
  up|demo|down|reset-demo)
    path=$(resolve "$slug"); script="$path/scripts/$command.sh"
    if [[ ! -f $script ]]; then echo "Project command is not implemented: $script" >&2; exit 2; fi
    if command -v timeout >/dev/null; then timeout 600 bash "$script"; else bash "$script"; fi
    ;;
  *) echo 'Usage: portfolio.sh doctor|list|verify|integration|mutation|up|demo|down|reset-demo [slug|all]' >&2; exit 2;;
esac
