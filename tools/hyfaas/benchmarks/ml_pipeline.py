import hashlib

from hyfaas_memory import read, write


def _features(payload):
    digest = hashlib.sha256(payload).digest()
    return [value / 255.0 for value in digest]


def main(args):
    context = args.get("hyfaas")
    payload = read(context=context) if context else args.get("payload", "").encode("utf-8")
    features = _features(payload)
    score = sum((idx + 1) * value for idx, value in enumerate(features)) / len(features)
    result = f"{score:.8f}".encode("ascii")
    if context:
        write(result, context=context)
        return {"score": score, "bytes": len(result)}
    return {"score": score}
