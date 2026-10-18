#!/usr/bin/env bash
# Coordinate CPU-bound Maven invocations within this shared checkout.
set -uo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ ${PORTFOLIO_BUILD_SLOT_HELD:-0} == 1 ]]; then exec "$@"; fi
if ! command -v flock >/dev/null; then exec "$@"; fi
mkdir -p "$root/.work/build-slots"
started=$SECONDS
announced=0
while true; do
  for slot in 0 1 2; do
    exec 9>"$root/.work/build-slots/$slot.lock"
    if flock -n 9; then
      export PORTFOLIO_BUILD_SLOT_HELD=1
      printf 'Shared build slot %s acquired (limit3).\n' "$((slot+1))" >&2
      "$@" &
      child=$!
      trap 'kill -TERM "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; exit 130' INT TERM
      wait "$child"
      outcome=$?
      exit "$outcome"
    fi
    exec 9>&-
  done
  if (( announced == 0 )); then echo 'Waiting for shared build slot (limit3).' >&2; announced=1; fi
  if (( SECONDS-started >= 1800 )); then echo 'Build slot wait exceeded1800s; inspect active builds.' >&2; exit 124; fi
  sleep 1
done
