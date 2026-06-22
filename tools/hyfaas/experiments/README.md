# HyFaaS Experiment Harness

This directory contains a reproducibility harness for the HyFaaS OpenWhisk prototype.

It covers the four workflows used in the TPDS paper:

- `Sort`: TeraSort-style map/shuffle/reduce data analytics.
- `MLp`: four-stage machine-learning training pipeline.
- `Vid`: video analytics serving workflow with a branch.
- `SLA`: eight-stage synthetic workflow for scalability evaluation.

The harness produces planner requests, raw HyDAG plans, summary CSV files, normalized latency/cost tables for HyFaaS planner variants, input-sensitivity runs, scalability runs, ablation rows, and orchestration-overhead measurements.

The included workload samples and configuration files are intended for controlled reproduction studies. They are not the original TPDS artifact bundle and do not include the external baseline systems or raw datasets used for the published measurements.

## Run The Planner Matrix

From the OpenWhisk tree:

```bash
python3 tools/hyfaas/experiments/run_experiments.py \
  --output tools/hyfaas/experiments/results/paper-default
```

Against a running controller:

```bash
python3 tools/hyfaas/experiments/run_experiments.py \
  --controller http://CONTROLLER_HOST:3233 \
  --output tools/hyfaas/experiments/results/paper-controller
```

Outputs:

- `summary.csv`: normalized latency and cost for each HyFaaS planner variant/workload/variant.
- `summary.json`: JSON copy of the same rows.
- `orchestration_overhead.csv`: planning overhead rows for Table III style reporting.
- `raw/*.json`: planner request, plan, and measured orchestration time.

## Execute Planned Workflows

First register actions:

```bash
bash tools/hyfaas/bin/register-actions.sh
```

Or use the REST helper:

```bash
export WSK_AUTH="<standalone-or-cluster-auth>"
python3 tools/hyfaas/bin/register-actions-rest.py --package hyfaas
```

Then invoke a planned workflow:

```bash
export WSK_AUTH="<standalone-or-cluster-auth>"
python3 tools/hyfaas/experiments/invoke_workflow.py \
  --api http://CONTROLLER_HOST:3233/api/v1/namespaces/_ \
  --plan tools/hyfaas/experiments/results/paper-controller/raw/video_analytics.json \
  --output tools/hyfaas/experiments/results/paper-controller/invocations/video_analytics.json \
  --max-parallelism 32
```

The invocation runner executes stages in HyDAG topological order and uses OpenWhisk blocking invocations for each partition. For multi-node runs, use the cluster deployment with mounted `HYFAAS_SHARED_MEMORY` and `HYFAAS_OBJECT_STORE` paths.

## External Baselines

The TPDS paper compares HyFaaS with Ditto, Orion, Sonic, and DataFlower. Those are separate systems and should be run through their own implementations. `baseline_adapter.py` defines the normalized contract:

```bash
python3 tools/hyfaas/experiments/baseline_adapter.py \
  --method sonic \
  --runner /path/to/sonic-runner \
  --request request.json \
  --output sonic-result.json
```

The external runner must print JSON:

```json
{
  "latencyMs": 1234.5,
  "cost": 0.00123
}
```

`run_experiments.py` intentionally reports only HyFaaS planner variants. It does not synthesize Ditto, Orion, Sonic, or DataFlower measurements. Use `baseline_adapter.py` to normalize external baseline outputs before combining them with HyFaaS results.
