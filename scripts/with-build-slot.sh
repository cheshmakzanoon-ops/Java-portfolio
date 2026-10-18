#!/usr/bin/env bash
# Coordinate CPU-bound Maven invocations within this shared checkout.
set -uo pipefail
if (( $# == 0 )); then echo 'Usage: with-build-slot.sh command [arguments...]' >&2; exit 2; fi
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ ${PORTFOLIO_BUILD_SLOT_HELD:-0} == 1 ]]; then exec "$@"; fi
if ! command -v flock >/dev/null; then exec "$@"; fi
mkdir -p "$root/.work/build-slots"
queue="$root/.work/build-slots/queue"
mkdir -p "$queue"
exec 8>"$root/.work/build-slots/queue.lock"
flock -x 8
sequence=0
if [[ -f $queue/sequence ]]; then read -r sequence < "$queue/sequence"; fi
sequence=$((sequence+1))
printf '%s\n' "$sequence" > "$queue/sequence"
printf -v ticket '%s/%020d-%d.request' "$queue" "$sequence" "$$"
printf '%s\n' "$$" > "$ticket"
flock -u 8
trap 'rm -f "$ticket"' EXIT
trap 'exit 130' INT TERM
started=$SECONDS
announced=0
while true; do
  flock -x 8
  requests=("$queue"/*.request)
  for request in "${requests[@]}"; do
    [[ -f $request ]] || continue
    read -r requester < "$request"
    if [[ ! $requester =~ ^[0-9]+$ ]] || ! kill -0 "$requester" 2>/dev/null; then rm -f "$request"; fi
  done
  requests=("$queue"/*.request)
  if [[ ${requests[0]} == "$ticket" ]]; then
   for slot in 0 1 2; do
    exec 9>"$root/.work/build-slots/$slot.lock"
    if flock -n 9; then
      rm -f "$ticket"
      flock -u 8
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
  fi
  flock -u 8
  if (( announced == 0 )); then echo 'Waiting for shared build slot (limit3).' >&2; announced=1; fi
  if (( SECONDS-started >= 1800 )); then echo 'Build slot wait exceeded1800s; inspect active builds.' >&2; exit 124; fi
  sleep 1
done
