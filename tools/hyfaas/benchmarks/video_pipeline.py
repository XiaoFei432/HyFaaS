import hashlib

from hyfaas_memory import read, write


def _payload(args):
    context = args.get("hyfaas")
    if context:
        return read(context=context)
    frames = int(args.get("frames", 64))
    fps = int(args.get("fps", 30))
    seed = str(args.get("seed", "video")).encode("utf-8")
    chunks = []
    for idx in range(frames):
        digest = hashlib.sha256(seed + idx.to_bytes(4, "little") + fps.to_bytes(2, "little")).digest()
        chunks.append(digest * max(1, fps // 15))
    return b"".join(chunks)


def _score(payload):
    digest = hashlib.sha256(payload).digest()
    return sum((idx + 1) * byte for idx, byte in enumerate(digest)) / 255.0


def main(args):
    context = args.get("hyfaas")
    stage = args.get("stage", "classify")
    payload = _payload(args)

    if stage == "extract":
        result = payload[:: max(1, int(args.get("sampleStride", 2)))]
    elif stage == "filter":
        threshold = int(args.get("threshold", 64))
        result = bytes(byte for byte in payload if byte >= threshold)
    elif stage == "classify":
        score = _score(payload)
        result = f"{score:.8f}".encode("ascii")
    elif stage == "merge":
        result = hashlib.sha256(payload).hexdigest().encode("ascii")
    else:
        result = payload

    if context:
        write(result, context=context)
        return {"stage": stage, "bytes": len(result)}
    output = {"stage": stage, "bytes": len(result)}
    if stage == "classify":
        output["score"] = float(result.decode("ascii"))
    return output
