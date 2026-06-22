#!/usr/bin/env python3
import argparse
import csv
import json
from pathlib import Path


def read_rows(path):
    with Path(path).open("r", newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def group(rows, keys):
    data = {}
    for row in rows:
        key = tuple(row[k] for k in keys)
        data.setdefault(key, []).append(row)
    return data


def main():
    parser = argparse.ArgumentParser(description="Summarize HyFaaS experiment CSV output.")
    parser.add_argument("summary_csv")
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    rows = read_rows(args.summary_csv)
    by_case = group(rows, ["experiment", "workload", "variant"])
    report = []
    for key, case_rows in sorted(by_case.items()):
        hyfaas = next((row for row in case_rows if row["method"] == "hyfaas"), None)
        if not hyfaas:
            continue
        variant_rows = [row for row in case_rows if row["method"] != "hyfaas"]
        best_variant_latency = min(float(row["latency_ms"]) for row in variant_rows)
        best_variant_cost = min(float(row["cost"]) for row in variant_rows)
        latency_gain = best_variant_latency / float(hyfaas["latency_ms"]) if float(hyfaas["latency_ms"]) else 0.0
        cost_gain = best_variant_cost / float(hyfaas["cost"]) if float(hyfaas["cost"]) else 0.0
        report.append(
            {
                "experiment": key[0],
                "workload": key[1],
                "variant": key[2],
                "hyfaas_latency_ms": float(hyfaas["latency_ms"]),
                "hyfaas_cost": float(hyfaas["cost"]),
                "best_variant_latency_over_hyfaas": round(latency_gain, 6),
                "best_variant_cost_over_hyfaas": round(cost_gain, 6),
            }
        )

    output = json.dumps(report, indent=2, sort_keys=True)
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
