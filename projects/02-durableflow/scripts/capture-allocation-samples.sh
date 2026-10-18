#!/usr/bin/env bash
# Post-measurement evidence only: preserve exact jfr JSON bytes without expansion on disk.
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: capture-allocation-samples.sh RECORDING CAPTURE_DIRECTORY' >&2; exit 2; }
recording=$1
capture=$2
[[ -s "$recording" && -d "$capture" ]] || { echo 'Recording or capture directory missing' >&2; exit 2; }
canonical="$capture/allocation-samples.json.gz"
partial="$canonical.partial"
[[ ! -e "$canonical" && ! -e "$partial" ]] || { echo 'Refusing to overwrite allocation evidence' >&2; exit 2; }
umask 077
set -o noclobber
if jfr print --json --events jdk.ObjectAllocationSample "$recording" | gzip -n -1 > "$partial"; then
  # A hard-link publication is atomic and refuses an existing canonical file.
  # Both names are in one capture directory; no archive or live JFR is altered.
  ln -T -- "$partial" "$canonical"
  rm -- "$partial"
else
  echo "Allocation producer/compressor failed; partial evidence retained: $partial" >&2
  exit 1
fi
