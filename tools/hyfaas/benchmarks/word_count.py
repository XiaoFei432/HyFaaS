from hyfaas_memory import read, write


def main(args):
    context = args.get("hyfaas")
    payload = read(context=context) if context else args.get("text", "").encode("utf-8")
    words = payload.decode("utf-8", errors="ignore").split()
    counts = {}
    for word in words:
        counts[word] = counts.get(word, 0) + 1
    result = "\n".join(f"{word}\t{count}" for word, count in sorted(counts.items())).encode("utf-8")
    if context:
        write(result, context=context)
        return {"bytes": len(result), "words": len(counts)}
    return {"counts": counts}
