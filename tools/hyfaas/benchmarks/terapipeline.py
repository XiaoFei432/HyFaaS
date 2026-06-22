from hyfaas_memory import read, write


def main(args):
    context = args.get("hyfaas")
    payload = read(context=context) if context else args.get("records", "").encode("utf-8")
    rows = [row for row in payload.splitlines() if row]
    rows.sort()
    result = b"\n".join(rows)
    if context:
        write(result, context=context)
        return {"records": len(rows), "bytes": len(result)}
    return {"records": [row.decode("utf-8", errors="ignore") for row in rows[:100]]}
