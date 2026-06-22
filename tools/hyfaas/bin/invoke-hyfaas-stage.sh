#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo "usage: $0 <action> <workflow> <activation> <stage> <partition>" >&2
  exit 2
fi

ACTION="$1"
WORKFLOW="$2"
ACTIVATION="$3"
STAGE="$4"
PARTITION="$5"

WSK="${WSK:-wsk}"
SHM="${HYFAAS_SHARED_MEMORY:-/mnt/hyfaas}"

tmpfile="$(mktemp)"
trap 'rm -f "$tmpfile"' EXIT

cat > "$tmpfile" <<JSON
{
  "hyfaas": {
    "workflow": "$WORKFLOW",
    "activation": "$ACTIVATION",
    "stage": "$STAGE",
    "partition": $PARTITION,
    "sharedMemoryRoot": "$SHM"
  }
}
JSON

"$WSK" action invoke "$ACTION" -r -P "$tmpfile"
