import base64
import json
import os
from pathlib import Path


def _safe(value):
    return base64.urlsafe_b64encode(str(value).encode("utf-8")).decode("ascii").rstrip("=")


def _memory_path(root, workflow, activation, stage, partition):
    return Path(root) / _safe(workflow) / _safe(activation) / _safe(stage) / str(partition) / "payload.bin"


def _object_path(root, workflow, activation, object_name, partition):
    return Path(root) / _safe(workflow) / _safe(activation) / _safe(object_name) / f"{partition}.bin"


def main(args):
    root = args.get("sharedMemoryRoot") or os.environ.get("HYFAAS_SHARED_MEMORY", "/mnt/hyfaas")
    storage = args.get("objectStoreRoot") or os.environ.get("HYFAAS_OBJECT_STORE", "/mnt/hyfaas-object-store")
    workflow = args["workflow"]
    activation = args["activation"]
    source_stage = args["sourceStage"]
    target_stage = args["targetStage"]
    partition = int(args.get("partition", 0))
    object_name = args.get("objectName", f"{source_stage}-to-{target_stage}")
    sender = bool(args.get("sender", True))

    if sender:
        source = _memory_path(root, workflow, activation, source_stage, partition)
        target = _object_path(storage, workflow, activation, object_name, partition)
        payload = source.read_bytes()
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix(".tmp")
        tmp.write_bytes(payload)
        tmp.replace(target)
        return {"mode": "send", "bytes": len(payload), "object": str(target)}

    source = _object_path(storage, workflow, activation, object_name, partition)
    target = _memory_path(root, workflow, activation, target_stage, partition)
    payload = source.read_bytes()
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    tmp.write_bytes(payload)
    tmp.replace(target)
    return {"mode": "receive", "bytes": len(payload), "local": str(target)}


if __name__ == "__main__":
    print(json.dumps(main(json.loads(os.environ.get("__OW_ARGS", "{}")))))
