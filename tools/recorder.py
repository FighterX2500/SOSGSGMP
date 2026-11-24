"""SOSGSGMP: print a recorder file (SOS-248) in order.

    python tools\\recorder.py <file> [--from STEP] [--to STEP] [--kind K [--kind K ...]]

<file> is either
  - a recorder file, %APPDATA%\\songsofsyx\\sosgsgmp\\recorder\\<host|join>-<player>.rec, or an incident's copy
    (<log name>-<step>-<why>.rec, also among a joining PC's reports on the host), or
  - a joining PC's stream on the host, reports\\<campaign>\\<slot>-<player>\\recorder.stream: recorder.stream.1 (the older
    half of its ring) is read first when it is there.
--kind takes a kind's name (net-in, hold-start, ...) or number; --from and --to keep the entries whose step is in
range (entries with no step are kept). It reads format 1 and says what it can't read.
Test tooling only: nothing here is part of the mod.
"""
import argparse
import os
import struct
import sys

MAGIC = b"SOSGSREC"
STREAM_MAGIC = b"SOSGSTRM"
ENTRY = struct.Struct(">qihhiqi")  # time ns, step, kind, peer, size, A, B: 32 bytes

KINDS = {
    1: "net-in", 2: "net-out", 3: "net-connect", 4: "net-disconnect", 5: "command", 6: "ai", 7: "army", 8: "transfer",
    9: "hashes", 10: "desync", 11: "hold-start", 12: "hold-end", 13: "speed", 14: "frame", 15: "line-same",
    16: "line-local", 17: "loading", 18: "session", 19: "types", 20: "gap",
}
TEXT_KINDS = {15, 16, 18, 19}
ARMY_KINDS = ["create", "move", "besiege", "raid", "intercept", "stop", "disband", "teleport"]


class Unreadable(Exception):
    pass


def read_rec(data):
    """(header dict, [entry tuples with text]) of a recorder file's bytes."""
    if data[:8] != MAGIC:
        raise Unreadable("not a recorder file (no %r at its start)" % MAGIC)
    version, size, start, role = struct.unpack_from(">iiqb", data, 8)
    if version != 1:
        raise Unreadable("recorder format %d; this reads format 1" % version)
    count, esize, psize, written, pwritten = struct.unpack_from(">iiiqq", data, 28)
    (nlen,) = struct.unpack_from(">h", data, 56)
    name = data[58:58 + nlen].decode("utf-8", "replace")
    if esize != ENTRY.size or len(data) < size + count * esize + psize:
        raise Unreadable("a format 1 file of the wrong size (entries of %d bytes, %d bytes in all)" % (esize, len(data)))
    payload_at = size + count * esize
    entries = []
    for i in range(max(0, written - count), written):
        e = ENTRY.unpack_from(data, size + (i % count) * esize)
        text = None
        if e[2] in TEXT_KINDS:
            if e[5] >= pwritten - psize:
                text = bytes(data[payload_at + (e[5] + k) % psize] for k in range(e[4])).decode("utf-8", "replace")
            else:
                text = "(its text was overwritten)"
        entries.append(e + (text,))
    header = {"start": start, "role": "host" if role == 0 else "joining PC", "name": name, "written": written,
              "kept": len(entries)}
    return header, entries


def read_stream(data, what):
    """[entry tuples with text] of a stream file's bytes: the magic and format, then whole records."""
    if data[:8] != STREAM_MAGIC:
        raise Unreadable("%s is not a recorder stream (no %r at its start)" % (what, STREAM_MAGIC))
    (version,) = struct.unpack_from(">i", data, 8)
    if version != 1:
        raise Unreadable("%s is stream format %d; this reads format 1" % (what, version))
    at, entries = 16, []
    while at + ENTRY.size <= len(data):
        e = ENTRY.unpack_from(data, at)
        at += ENTRY.size
        text = None
        if e[2] in TEXT_KINDS:
            text = data[at:at + e[4]].decode("utf-8", "replace")
            at += e[4]
        entries.append(e + (text,))
    if at != len(data):
        print("recorder: %s ends in a cut record (%d bytes left)" % (what, len(data) - at), file=sys.stderr)
    return entries


def describe(e, types):
    time, step, kind, peer, size, a, b, text = e
    if kind in (1, 2):
        return "%s from/to peer %d, %d bytes" % (types.get(a, "type %d" % a), peer, size)
    if kind in (3, 4):
        return "peer %d" % peer
    if kind == 5:
        return "seq %d, PC %d, division %d, command %d%s" % (a, peer, size, b & 0xFFFF, ", skipped" if b >> 16 else "")
    if kind == 6:
        return "seq %d, PC %d, division %d, kind %d%s" % (a, peer, size, b & 0xFFFF, ", skipped" if b >> 16 else "")
    if kind == 7:
        k = ARMY_KINDS[size] if 0 <= size < len(ARMY_KINDS) else "kind %d" % size
        return "army %d of faction %d: %s to tile %d,%d, target %d" % (a & 0xFFFFFFFF, peer, k, a >> 48 & 0xFFFF, a >> 32 & 0xFFFF, b)
    if kind == 9:
        return "compared %d, missed %d" % (a, b)
    if kind == 10:
        return "at step %d" % a
    if kind == 11:
        return "at step %d" % a
    if kind == 12:
        return "at step %d, %d ms" % (a, b)
    if kind == 13:
        return "speed %.2f%s" % (a / 100.0, ", paused" if b else "")
    if kind == 14:
        return "%d ms" % a
    if kind == 17:
        return "key %d %s" % (a, "opened" if b else "closed")
    if kind == 20:
        return "%d entries skipped" % a
    if text is not None:
        return text
    return "peer %d, size %d, A %d, B %d" % (peer, size, a, b)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--from", dest="lo", type=int, default=None)
    ap.add_argument("--to", dest="hi", type=int, default=None)
    ap.add_argument("--kind", action="append", default=[])
    args = ap.parse_args(argv)
    names = {v: k for k, v in KINDS.items()}
    kinds = set()
    for k in args.kind:
        if k.isdigit():
            kinds.add(int(k))
        elif k in names:
            kinds.add(names[k])
        else:
            ap.error("no kind %r; the kinds: %s" % (k, ", ".join(KINDS.values())))
    try:
        if os.path.basename(args.file).startswith("recorder.stream"):
            entries = []
            older = args.file + ".1" if not args.file.endswith(".1") else None
            for path in ([older] if older and os.path.exists(older) else []) + [args.file]:
                with open(path, "rb") as f:
                    entries += read_stream(f.read(), path)
            print("recorder stream %s: %d records" % (args.file, len(entries)))
        else:
            with open(args.file, "rb") as f:
                header, entries = read_rec(f.read())
            print("recorder %s: %s %s, session start %d ms, %d entries written, %d kept" % (
                args.file, header["role"], header["name"], header["start"], header["written"], header["kept"]))
    except (OSError, Unreadable, struct.error) as e:
        print("recorder: can't read %s: %s" % (args.file, e), file=sys.stderr)
        return 2
    types = {}
    for e in entries:
        if e[2] == 19 and e[7]:
            for part in e[7].split()[2:]:
                n, _, name = part.partition("=")
                if n.isdigit():
                    types[int(n)] = name
    for e in entries:
        time, step, kind = e[0], e[1], e[2]
        if kinds and kind not in kinds:
            continue
        if step >= 0 and (args.lo is not None and step < args.lo or args.hi is not None and step > args.hi):
            continue
        print("%10.3f s  %8s  %-14s %s" % (time / 1e9, step if step >= 0 else "-", KINDS.get(kind, "kind %d" % kind),
                                           describe(e, types)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
