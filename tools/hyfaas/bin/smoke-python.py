#!/usr/bin/env python3
import json
import shutil
import sys
import tempfile
from pathlib import Path


HYFAAS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HYFAAS_ROOT / "runtime"))
sys.path.insert(0, str(HYFAAS_ROOT / "proxy"))
sys.path.insert(0, str(HYFAAS_ROOT / "benchmarks"))

import hyfaas_memory
import ml_pipeline
import proxy
import slapp_pipeline
import sort_pipeline
import terapipeline
import video_pipeline
import word_count


def main():
    shared_root = Path(tempfile.mkdtemp(prefix="hyfaas-shm-"))
    object_root = Path(tempfile.mkdtemp(prefix="hyfaas-store-"))

    try:
        source_context = {
            "workflow": "video-analytics",
            "activation": "activation-001",
            "stage": "extract",
            "partition": 0,
            "root": str(shared_root),
        }
        target_context = dict(source_context, stage="filter")

        payload = b"alpha beta alpha\nframe-3 frame-1\n"
        hyfaas_memory.write(payload, context=source_context)
        assert hyfaas_memory.read(context=source_context) == payload

        send = proxy.main(
            {
                "sharedMemoryRoot": str(shared_root),
                "objectStoreRoot": str(object_root),
                "workflow": "video-analytics",
                "activation": "activation-001",
                "sourceStage": "extract",
                "targetStage": "filter",
                "partition": 0,
                "objectName": "extract-to-filter",
                "sender": True,
            }
        )
        receive = proxy.main(
            {
                "sharedMemoryRoot": str(shared_root),
                "objectStoreRoot": str(object_root),
                "workflow": "video-analytics",
                "activation": "activation-001",
                "sourceStage": "extract",
                "targetStage": "filter",
                "partition": 0,
                "objectName": "extract-to-filter",
                "sender": False,
            }
        )
        assert send["bytes"] == receive["bytes"] == len(payload)
        assert hyfaas_memory.read(context=target_context) == payload

        word_count_direct = word_count.main({"text": "red blue red"})
        assert word_count_direct["counts"] == {"blue": 1, "red": 2}

        word_count_context = word_count.main({"hyfaas": target_context})
        assert word_count_context["words"] >= 3

        sort_direct = terapipeline.main({"records": "b\na\nc\n"})
        assert sort_direct["records"] == ["a", "b", "c"]

        ml_direct = ml_pipeline.main({"payload": "frame bytes"})
        assert ml_direct["score"] > 0

        sort_job = sort_pipeline.main({"sizeBytes": 4096, "stage": "reduce"})
        assert sort_job["records"] > 0

        video_job = video_pipeline.main({"frames": 8, "fps": 30, "stage": "classify"})
        assert video_job["score"] > 0

        slapp_job = slapp_pipeline.main({"sizeBytes": 2048, "stage": "stage0"})
        assert slapp_job["bytes"] > 0

        print(
            json.dumps(
                {
                    "status": "passed",
                    "proxyBytes": send["bytes"],
                    "wordCountKeys": sorted(word_count_direct["counts"].keys()),
                    "terapipelineRecords": sort_direct["records"],
                    "mlScore": ml_direct["score"],
                    "sortRecords": sort_job["records"],
                    "videoScore": video_job["score"],
                    "slappDigest": slapp_job["digest"],
                },
                indent=2,
            )
        )
    finally:
        shutil.rmtree(shared_root, ignore_errors=True)
        shutil.rmtree(object_root, ignore_errors=True)


if __name__ == "__main__":
    main()
