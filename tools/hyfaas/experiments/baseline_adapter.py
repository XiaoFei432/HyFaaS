#!/usr/bin/env python3
import argparse
import json
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Invoke an external baseline runner and normalize its result schema.")
    parser.add_argument("--method", required=True, choices=["ditto", "orion", "sonic", "dataflower"])
    parser.add_argument("--runner", required=True, help="Executable or script for the external baseline implementation.")
    parser.add_argument("--request", required=True, help="HyFaaS planner request JSON passed to the adapter.")
    parser.add_argument("--output", required=True)
    args, extra = parser.parse_known_args()

    request = json.loads(Path(args.request).read_text(encoding="utf-8"))
    cmd = [args.runner, "--method", args.method, "--request", args.request] + extra
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        sys.exit(proc.returncode)

    try:
        result = json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"baseline runner must print JSON: {exc}\nstdout={proc.stdout}")

    normalized = {
        "method": args.method,
        "latencyMs": result["latencyMs"],
        "cost": result["cost"],
        "raw": result,
        "requestWorkflowStages": len(request["workflow"]["stages"]),
    }
    Path(args.output).write_text(json.dumps(normalized, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(normalized, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
