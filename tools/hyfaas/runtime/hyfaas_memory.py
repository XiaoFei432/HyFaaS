import base64
import os
from pathlib import Path


def _safe(value):
    return base64.urlsafe_b64encode(str(value).encode("utf-8")).decode("ascii").rstrip("=")


def context_from_env():
    return {
        "workflow": os.environ["HYFAAS_WORKFLOW"],
        "activation": os.environ["HYFAAS_ACTIVATION"],
        "stage": os.environ["HYFAAS_STAGE"],
        "partition": int(os.environ.get("HYFAAS_PARTITION", "0")),
        "root": os.environ.get("HYFAAS_SHARED_MEMORY", "/mnt/hyfaas"),
    }


def normalize_context(context=None):
    if context is None:
        return context_from_env()
    return {
        "workflow": context["workflow"],
        "activation": context["activation"],
        "stage": context["stage"],
        "partition": int(context.get("partition", 0)),
        "root": context.get("root") or context.get("sharedMemoryRoot") or os.environ.get("HYFAAS_SHARED_MEMORY", "/mnt/hyfaas"),
    }


def path(stage=None, partition=None, context=None):
    ctx = normalize_context(context)
    return (
        Path(ctx["root"])
        / _safe(ctx["workflow"])
        / _safe(ctx["activation"])
        / _safe(stage or ctx["stage"])
        / str(ctx["partition"] if partition is None else partition)
        / "payload.bin"
    )


def read(stage=None, partition=None, context=None):
    return path(stage, partition, context).read_bytes()


def write(payload, stage=None, partition=None, context=None):
    target = path(stage, partition, context)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    tmp.write_bytes(payload)
    tmp.replace(target)
    return str(target)
