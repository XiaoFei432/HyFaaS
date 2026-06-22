#!/usr/bin/env python3
import argparse
import base64
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path


DEFAULT_AUTH = os.environ.get("WSK_AUTH") or os.environ.get("OPENWHISK_AUTH")


def _request(method, url, auth, body=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "Authorization": "Basic " + base64.b64encode(auth.encode("utf-8")).decode("ascii"),
    }
    if payload is not None:
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(url, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return response.status, json.loads(raw.decode("utf-8")) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed with HTTP {exc.code}: {detail}") from exc


def _action_url(api, package, name, query=None):
    package_path = urllib.parse.quote(package, safe="")
    action_path = urllib.parse.quote(name, safe="")
    suffix = f"/actions/{package_path}/{action_path}"
    if query:
        suffix += "?" + urllib.parse.urlencode(query)
    return api.rstrip("/") + suffix


def _package_url(api, package):
    package_path = urllib.parse.quote(package, safe="")
    return api.rstrip("/") + f"/packages/{package_path}?overwrite=true"


def _zip_action(action_file, helper_file):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(action_file, "__main__.py")
        archive.write(helper_file, "hyfaas_memory.py")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _put_package(args):
    body = {"name": args.package, "publish": False, "parameters": [], "annotations": []}
    return _request("PUT", _package_url(args.api, args.package), args.auth, body)


def _put_action(args, name, exec_body):
    body = {
        "name": name,
        "publish": False,
        "exec": exec_body,
        "parameters": [],
        "annotations": [{"key": "hyfaas", "value": True}],
    }
    return _request(
        "PUT",
        _action_url(args.api, args.package, name, {"overwrite": "true"}),
        args.auth,
        body,
    )


def register_actions(args):
    hyfaas_root = Path(__file__).resolve().parents[1]
    proxy_file = hyfaas_root / "proxy" / "proxy.py"
    bench_dir = hyfaas_root / "benchmarks"
    helper_file = hyfaas_root / "runtime" / "hyfaas_memory.py"

    _put_package(args)
    _put_action(
        args,
        "proxy",
        {
            "kind": args.kind,
            "code": proxy_file.read_text(encoding="utf-8"),
            "main": "main",
            "binary": False,
        },
    )

    for name in ("word_count", "terapipeline", "ml_pipeline", "sort_pipeline", "video_pipeline", "slapp_pipeline"):
        _put_action(
            args,
            name,
            {
                "kind": args.kind,
                "code": _zip_action(bench_dir / f"{name}.py", helper_file),
                "main": "main",
                "binary": True,
            },
        )


def invoke(args, name, params):
    _, result = _request(
        "POST",
        _action_url(args.api, args.package, name, {"blocking": "true", "result": "true"}),
        args.auth,
        params,
    )
    return result


def smoke(args):
    return {
        "word_count": invoke(args, "word_count", {"text": "red blue red"}),
        "terapipeline": invoke(args, "terapipeline", {"records": "b\na\nc\n"}),
        "ml_pipeline": invoke(args, "ml_pipeline", {"payload": "hyfaas smoke"}),
        "sort_pipeline": invoke(args, "sort_pipeline", {"sizeBytes": 2048, "stage": "reduce"}),
        "video_pipeline": invoke(args, "video_pipeline", {"frames": 8, "fps": 30, "stage": "classify"}),
        "slapp_pipeline": invoke(args, "slapp_pipeline", {"sizeBytes": 2048, "stage": "stage0"}),
    }


def parse_args():
    parser = argparse.ArgumentParser(description="Register and smoke-test HyFaaS actions through the OpenWhisk REST API.")
    parser.add_argument("--api", default="http://localhost:3233/api/v1/namespaces/_")
    parser.add_argument("--auth", default=DEFAULT_AUTH, help="OpenWhisk auth key. Defaults to WSK_AUTH or OPENWHISK_AUTH.")
    parser.add_argument("--package", default=None, help="OpenWhisk package name. Defaults to a fresh hyfaas-smoke-* package.")
    parser.add_argument("--kind", default="python:3.10")
    parser.add_argument("--skip-smoke", action="store_true")
    args = parser.parse_args()
    if not args.auth:
        parser.error("--auth is required unless WSK_AUTH or OPENWHISK_AUTH is set")
    if args.package is None:
        args.package = f"hyfaas-smoke-{os.getpid()}"
    return args


def main():
    args = parse_args()
    register_actions(args)
    output = {"registered": True, "package": args.package, "kind": args.kind}
    if not args.skip_smoke:
        output["smoke"] = smoke(args)
    print(json.dumps(output, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
