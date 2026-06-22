#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/runtime"
BENCH_DIR="$ROOT_DIR/benchmarks"

WSK="${WSK:-wsk}"
NAMESPACE="${HYFAAS_NAMESPACE:-hyfaas}"
PYTHON_KIND="${HYFAAS_PYTHON_KIND:-python:3.10}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

"$WSK" package update "$NAMESPACE"
"$WSK" action update "$NAMESPACE/proxy" "$ROOT_DIR/proxy/proxy.py" --kind "$PYTHON_KIND"

for action in word_count terapipeline ml_pipeline sort_pipeline video_pipeline slapp_pipeline; do
  cp "$BENCH_DIR/$action.py" "$tmpdir/__main__.py"
  cp "$RUNTIME_DIR/hyfaas_memory.py" "$tmpdir/hyfaas_memory.py"
  (cd "$tmpdir" && zip -q -r "$action.zip" __main__.py hyfaas_memory.py)
  "$WSK" action update "$NAMESPACE/$action" "$tmpdir/$action.zip" --kind "$PYTHON_KIND"
  rm -f "$tmpdir/__main__.py" "$tmpdir/hyfaas_memory.py" "$tmpdir/$action.zip"
done

echo "Registered HyFaaS actions under package '$NAMESPACE'."
