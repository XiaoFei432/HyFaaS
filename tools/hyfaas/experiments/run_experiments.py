#!/usr/bin/env python3
import argparse
import csv
import json
import math
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OPENWHISK_ROOT = ROOT.parents[1]
EXPERIMENTS = ROOT / "experiments"
WORKLOADS = EXPERIMENTS / "workloads"
CONFIGS = EXPERIMENTS / "config"


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8")


def write_csv(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def request_for(workload, config):
    return {
        "workflow": workload["workflow"],
        "cluster": config["cluster"],
        "pricing": config["pricing"],
        "samples": workload.get("samples", []),
        "profiling": config["profiling"],
        "optimizer": config["optimizer"],
    }


def scaled_workload(workload, *, size_gb=None, fps=None, image_set=None, servers=None):
    workload = json.loads(json.dumps(workload))
    if size_gb is not None:
        scale = (size_gb * 1024**3) / max(1, workload.get("defaultInput", {}).get("datasetBytes", size_gb * 1024**3))
        workload["defaultInput"]["datasetBytes"] = int(size_gb * 1024**3)
        for edge in workload["workflow"]["edges"]:
            edge["bytes"] = max(1, int(edge["bytes"] * scale))
        for sample in workload.get("samples", []):
            sample["inputBytes"] = max(1, int(sample["inputBytes"] * scale))
            sample["computeMs"] = sample["computeMs"] * math.sqrt(max(0.25, scale))
    if fps is not None:
        old = workload.get("defaultInput", {}).get("fps", 60)
        factor = max(0.25, fps / old)
        workload["defaultInput"]["fps"] = fps
        for edge in workload["workflow"]["edges"]:
            edge["bytes"] = max(1, int(edge["bytes"] * factor))
        for sample in workload.get("samples", []):
            sample["inputBytes"] = max(1, int(sample["inputBytes"] * factor))
            sample["computeMs"] = sample["computeMs"] * (0.85 + 0.15 * factor)
            sample.setdefault("contentFeatures", {})["fps"] = fps
    if image_set is not None:
        workload["defaultInput"]["dataset"] = image_set
        factor = 1.0 if image_set.lower().startswith("cifar") else 1.22
        for sample in workload.get("samples", []):
            sample["computeMs"] = sample["computeMs"] * factor
            sample.setdefault("contentFeatures", {})["datasetFactor"] = factor
    return workload


def plan_via_controller(request, controller):
    data = json.dumps(request).encode("utf-8")
    req = urllib.request.Request(
        controller.rstrip("/") + "/hyfaas/plan",
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=120) as response:
        body = response.read().decode("utf-8")
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return json.loads(body), elapsed_ms


def plan_via_gradle(request, openwhisk_dir):
    openwhisk_dir = Path(openwhisk_dir).resolve()
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
        json.dump(request, handle)
        request_path = handle.name
    cmd = [str(openwhisk_dir / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
    cmd += [":core:controller:hyfaasPlan", f"-PhyfaasRequest={request_path}", "--no-daemon", "--quiet"]
    started = time.perf_counter()
    try:
        proc = subprocess.run(cmd, cwd=str(openwhisk_dir), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    finally:
        Path(request_path).unlink(missing_ok=True)
    if proc.returncode != 0:
        raise RuntimeError(
            "planner command failed\n"
            f"command: {' '.join(cmd)}\n"
            f"exitCode: {proc.returncode}\n"
            f"stdout:\n{proc.stdout}\n"
            f"stderr:\n{proc.stderr}")
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    text = proc.stdout.strip()
    start = text.find("{")
    if start < 0:
        raise RuntimeError(f"planner output did not contain JSON:\nSTDOUT:\n{text}\nSTDERR:\n{proc.stderr}")
    plan, _ = json.JSONDecoder().raw_decode(text[start:])
    return plan, elapsed_ms


def plan_request(request, args):
    if args.controller:
        return plan_via_controller(request, args.controller)
    return plan_via_gradle(request, args.openwhisk)


def plan_latency(plan, key):
    return plan[key]["stageConfigs"]


def path_latency(plan, key):
    latencies = {stage: cfg["latencyMs"] for stage, cfg in plan_latency(plan, key).items()}
    edges = plan["hyDag"]["edges"]
    stages = [stage["id"] for stage in plan["hyDag"]["stages"]]
    incoming = {stage: [] for stage in stages}
    outgoing = {stage: [] for stage in stages}
    for edge in edges:
        incoming[edge["to"]].append(edge["from"])
        outgoing[edge["from"]].append(edge["to"])
    ready = sorted([stage for stage in stages if not incoming[stage]])
    order = []
    while ready:
        stage = ready.pop(0)
        order.append(stage)
        for nxt in outgoing[stage]:
            incoming[nxt].remove(stage)
            if not incoming[nxt]:
                ready.append(nxt)
                ready.sort()
    dist = {stage: float("-inf") for stage in stages}
    dist[plan["hyDag"]["start"]] = latencies.get(plan["hyDag"]["start"], 0.0)
    for stage in order:
        for nxt in outgoing[stage]:
            dist[nxt] = max(dist[nxt], dist[stage] + latencies.get(nxt, 0.0))
    return max(0.0, dist[plan["hyDag"]["end"]])


def plan_cost(plan, key):
    return sum(cfg["cost"] for cfg in plan[key]["stageConfigs"].values())


def result_from_plan(plan, method):
    if method == "hyfaas-s":
        return path_latency(plan, "saturatedPlan"), plan_cost(plan, "saturatedPlan")
    if method == "hyfaas-l":
        return path_latency(plan, "lhpPlan"), plan_cost(plan, "lhpPlan")
    if method == "hyfaas":
        return plan["workflowLatencyMs"], plan["workflowCost"]
    raise ValueError(
        f"unknown method {method}. This runner only reports HyFaaS planner variants "
        "('hyfaas-s', 'hyfaas-l', 'hyfaas'). Use baseline_adapter.py for external baselines.")


def summarize_case(case, plan, overhead_ms, methods):
    rows = []
    hyfaas_latency, hyfaas_cost = result_from_plan(plan, "hyfaas")
    for method in methods:
        latency, cost = result_from_plan(plan, method)
        rows.append(
            {
                **case,
                "method": method,
                "latency_ms": round(latency, 6),
                "cost": round(cost, 12),
                "normalized_latency": round(latency / hyfaas_latency, 6) if hyfaas_latency else 0,
                "normalized_cost": round(cost / hyfaas_cost, 6) if hyfaas_cost else 0,
                "hyDagStages": len(plan["hyDag"]["stages"]),
                "hyDagEdges": len(plan["hyDag"]["edges"]),
                "orchestration_ms": round(overhead_ms, 6),
            }
        )
    return rows


def run_matrix(args):
    config = load_json(args.config)
    out = Path(args.output)
    methods = args.methods or config["methods"]
    all_rows = []
    overhead_rows = []

    for workload_name in config["workloads"]:
        workload = load_json(WORKLOADS / f"{workload_name}.json")
        request = request_for(workload, config)
        plan, overhead = plan_request(request, args)
        write_json(out / "raw" / f"{workload_name}.json", {"request": request, "plan": plan, "orchestrationMs": overhead})
        case = {"experiment": "overall", "workload": workload["paperName"], "variant": "default"}
        all_rows.extend(summarize_case(case, plan, overhead, methods))
        overhead_rows.append({**case, "orchestration_ms": round(overhead, 6)})

    sort = load_json(WORKLOADS / "sort.json")
    for size in config["sensitivity"]["sortSizesGb"]:
        workload = scaled_workload(sort, size_gb=size)
        request = request_for(workload, config)
        plan, overhead = plan_request(request, args)
        name = f"sort-{size}gb"
        write_json(out / "raw" / f"{name}.json", {"request": request, "plan": plan, "orchestrationMs": overhead})
        case = {"experiment": "input-size", "workload": "Sort", "variant": f"{size}GB"}
        all_rows.extend(summarize_case(case, plan, overhead, methods))

    vid = load_json(WORKLOADS / "video_analytics.json")
    for fps in config["sensitivity"]["videoFps"]:
        workload = scaled_workload(vid, fps=fps)
        request = request_for(workload, config)
        plan, overhead = plan_request(request, args)
        name = f"vid-{fps}fps"
        write_json(out / "raw" / f"{name}.json", {"request": request, "plan": plan, "orchestrationMs": overhead})
        case = {"experiment": "input-content", "workload": "Vid", "variant": f"{fps}FPS"}
        all_rows.extend(summarize_case(case, plan, overhead, methods))

    mlp = load_json(WORKLOADS / "ml_pipeline.json")
    for image_set in config["sensitivity"]["imageSets"]:
        workload = scaled_workload(mlp, image_set=image_set)
        request = request_for(workload, config)
        plan, overhead = plan_request(request, args)
        name = f"mlp-{image_set.lower()}"
        write_json(out / "raw" / f"{name}.json", {"request": request, "plan": plan, "orchestrationMs": overhead})
        case = {"experiment": "input-content", "workload": "MLp", "variant": image_set}
        all_rows.extend(summarize_case(case, plan, overhead, methods))

    sla = load_json(WORKLOADS / "slapp.json")
    for servers in config["sensitivity"]["slaServers"]:
        cfg = json.loads(json.dumps(config))
        cfg["cluster"]["servers"] = servers
        request = request_for(sla, cfg)
        plan, overhead = plan_request(request, args)
        name = f"sla-{servers}servers"
        write_json(out / "raw" / f"{name}.json", {"request": request, "plan": plan, "orchestrationMs": overhead})
        case = {"experiment": "scalability", "workload": "SLA", "variant": f"{servers}servers"}
        all_rows.extend(summarize_case(case, plan, overhead, methods))
        overhead_rows.append({**case, "orchestration_ms": round(overhead, 6)})

    write_csv(out / "summary.csv", all_rows)
    write_csv(out / "orchestration_overhead.csv", overhead_rows)
    write_json(out / "summary.json", all_rows)
    return out


def parse_args():
    parser = argparse.ArgumentParser(description="Run the HyFaaS planner experiment matrix.")
    parser.add_argument("--config", default=str(CONFIGS / "paper-default.json"))
    parser.add_argument("--output", default=str(EXPERIMENTS / "results" / time.strftime("%Y%m%d-%H%M%S")))
    parser.add_argument("--openwhisk", default=str(OPENWHISK_ROOT))
    parser.add_argument("--controller", default=None, help="Controller base URL, for example http://localhost:3233")
    parser.add_argument("--methods", nargs="*", default=None)
    return parser.parse_args()


def main():
    args = parse_args()
    out = run_matrix(args)
    print(json.dumps({"status": "passed", "output": str(out)}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
