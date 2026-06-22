from hyfaas_memory import read, write


def _payload(args):
    context = args.get("hyfaas")
    if context:
        return read(context=context)
    records = args.get("records")
    if records is not None:
        return str(records).encode("utf-8")
    size = int(args.get("sizeBytes", 1024 * 1024))
    seed = int(args.get("seed", 17))
    rows = []
    value = seed
    while sum(len(row) + 1 for row in rows) < size:
        value = (value * 1103515245 + 12345) & 0x7FFFFFFF
        rows.append(f"{value:010d}\tvalue-{value % 100000:05d}")
    return ("\n".join(rows) + "\n").encode("utf-8")


def main(args):
    context = args.get("hyfaas")
    payload = _payload(args)
    rows = [row for row in payload.splitlines() if row]
    stage = args.get("stage", "sort")

    if stage == "map":
        rows = [row for row in rows if row]
    elif stage == "reduce":
        rows = sorted(rows)
    else:
        rows = sorted(rows)

    result = b"\n".join(rows[: int(args.get("limit", len(rows)))])
    if context:
        write(result, context=context)
        return {"records": len(rows), "bytes": len(result), "stage": stage}
    return {"records": len(rows), "first": result[:128].decode("utf-8", errors="ignore"), "stage": stage}
