#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <workflow-request.json> [controller-url]" >&2
  exit 2
fi

REQUEST="$1"
CONTROLLER="${2:-${HYFAAS_CONTROLLER:-http://localhost:3233}}"
AUTH_ARGS=()
if [[ -n "${HYFAAS_CONTROLLER_USER:-}" || -n "${HYFAAS_CONTROLLER_PASSWORD:-}" ]]; then
  if [[ -z "${HYFAAS_CONTROLLER_USER:-}" || -z "${HYFAAS_CONTROLLER_PASSWORD:-}" ]]; then
    echo "HYFAAS_CONTROLLER_USER and HYFAAS_CONTROLLER_PASSWORD must be set together" >&2
    exit 2
  fi
  AUTH_ARGS=(-u "$HYFAAS_CONTROLLER_USER:$HYFAAS_CONTROLLER_PASSWORD")
fi

curl -fsS \
  "${AUTH_ARGS[@]}" \
  -H 'Content-Type: application/json' \
  -d @"$REQUEST" \
  "$CONTROLLER/hyfaas/plan"
