#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
exec 9>"$PROJECT/target/lifecycle.lock"
flock -x 9
primary=0
remember_signal() {
 ((primary != 0)) || primary=$1
 trap '' INT TERM
}
trap 'remember_signal 130' INT
trap 'remember_signal 143' TERM
stop_owned_api || { result=$?; ((primary != 0)) || primary=$result; }
"${COMPOSE[@]}" stop || { result=$?; ((primary != 0)) || primary=$result; }
exit "$primary"
