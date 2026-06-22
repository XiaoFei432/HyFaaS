import hashlib

from hyfaas_memory import read, write


def _payload(args):
    context = args.get("hyfaas")
    if context:
        return read(context=context)
    size = int(args.get("sizeBytes", 256 * 1024))
    seed = str(args.get("seed", "slapp")).encode("utf-8")
    block = hashlib.sha256(seed).digest()
    return (block * (size // len(block) + 1))[:size]


def _mix(payload, rounds):
    digest = payload
    for idx in range(rounds):
        digest = hashlib.sha256(digest + idx.to_bytes(4, "little")).digest()
    return digest


def main(args):
    context = args.get("hyfaas")
    stage = str(args.get("stage", "stage0"))
    payload = _payload(args)
    rounds = int(args.get("rounds", 64 + 7 * sum(ord(ch) for ch in stage)))
    result = _mix(payload[:4096], max(1, rounds % 512))

    if context:
        write(result, context=context)
        return {"stage": stage, "bytes": len(result), "rounds": rounds}
    return {"stage": stage, "bytes": len(result), "digest": result.hex()[:16], "rounds": rounds}
