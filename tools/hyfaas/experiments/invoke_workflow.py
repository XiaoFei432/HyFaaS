#!/usr/bin/env python3
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


DEFAULT_AUTH = os.environ.get("WSK_AUTH") or os.environ.get("OPENWHISK_AUTH")


def request(method, url, auth, body=None, timeout=120):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "Authorization": "Basic " + base64.b64encode(auth.encode("utf-8")).decode("ascii"),
    }
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read()
            return response.status, json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed with HTTP {exc.code}: {detail}") from exc


def action_url(api, action, query=None):
    if action.startswith("/"):
        action = action.split("/", 2)[-1]
    parts = [urllib.parse.quote(part, safe="") for part in action.split("/")]
    suffix = "/actions/" + "/".join(parts)
    if query:
        suffix += "?" + urllib.parse.urlencode(query)
    return api.rstrip("/") + suffix


def topo(hy_dag):
    stages = [stage["id"] for stage in hy_dag["stages"]]
    incoming = {stage: set() for stage in stages}
    outgoing = {stage: [] for stage in stages}
    for edge in hy_dag["edges"]:
        incoming[edge["to"]].add(edge["from"])
        outgoing[edge["from"]].append(edge["to"])
    ready = sorted(stage for stage in stages if not incoming[stage])
    order = []
    while ready:
        stage = ready.pop(0)
        order.append(stage)
        for nxt in outgoing[stage]:
            incoming[nxt].remove(stage)
            if not incoming[nxt]:
                ready.append(nxt)
                ready.sort()
    return order


def invoke_stage(args, stage, partition, workflow, activation):
    action = stage.get("action")
    if not action:
        return {"stage": stage["id"], "partition": partition, "skipped": True, "latencyMs": 0.0}
    params = {
        "hyfaas": {
            "workflow": workflow,
            "activation": activation,
            "stage": stage["id"],
            "partition": partition,
            "sharedMemoryRoot": args.shared_memory,
        },
        "workflow": workflow,
        "activation": activation,
        "stage": stage["id"],
        "partition": partition,
        "sourceStage": stage.get("source") or stage["id"],
        "targetStage": stage.get("target") or stage["id"],
        "objectStoreRoot": args.object_store,
        "sharedMemoryRoot": args.shared_memory,
        "sender": True,
        "sizeBytes": args.synthetic_bytes,
        "records": "b\na\nc\n",
        "payload": "hyfaas workflow payload",
    }
    started = time.perf_counter()
    result = request(
        "POST",
        action_url(args.api, action, {"blocking": "true", "result": "true"}),
        args.auth,
        params,
        timeout=args.timeout,
    )[1]
    elapsed = (time.perf_counter() - started) * 1000.0
    return {"stage": stage["id"], "partition": partition, "latencyMs": elapsed, "result": result}


def main():
    parser = argparse.ArgumentParser(description="Invoke a planned HyFaaS workflow through OpenWhisk REST APIs.")
    parser.add_argument("--api", default="http://localhost:3233/api/v1/namespaces/_")
    parser.add_argument("--auth", default=DEFAULT_AUTH, help="OpenWhisk auth key. Defaults to WSK_AUTH or OPENWHISK_AUTH.")
    parser.add_argument("--plan", required=True, help="Raw plan JSON from run_experiments.py or direct /hyfaas/plan output.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--workflow", default="hyfaas-workflow")
    parser.add_argument("--activation", default=None)
    parser.add_argument("--shared-memory", default="/mnt/hyfaas")
    parser.add_argument("--object-store", default="/mnt/hyfaas-object-store")
    parser.add_argument("--max-parallelism", type=int, default=8)
    parser.add_argument("--synthetic-bytes", type=int, default=4096)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if not args.auth:
        parser.error("--auth is required unless WSK_AUTH or OPENWHISK_AUTH is set")

    raw = json.loads(Path(args.plan).read_text(encoding="utf-8"))
    plan = raw.get("plan", raw)
    hy_dag = plan["hyDag"]
    stage_by_id = {stage["id"]: stage for stage in hy_dag["stages"]}
    activation = args.activation or f"activation-{os.getpid()}-{int(time.time())}"
    events = []
    workflow_started = time.perf_counter()

    for stage_id in topo(hy_dag):
        stage = stage_by_id[stage_id]
        if stage["kind"] in ("start", "end"):
            continue
        parallelism = min(args.max_parallelism, max(1, int(stage.get("baselineParallelism", 1))))
        with ThreadPoolExecutor(max_workers=parallelism) as pool:
            futures = [pool.submit(invoke_stage, args, stage, part, args.workflow, activation) for part in range(parallelism)]
            for future in as_completed(futures):
                events.append(future.result())

    output = {
        "workflow": args.workflow,
        "activation": activation,
        "latencyMs": (time.perf_counter() - workflow_started) * 1000.0,
        "events": events,
    }
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(output, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
