"""SOS-23 spike: where do two game saves first differ?

    python compare_saves.py A.save B.save [more.save ...]

A save is deflate(4-byte size + buffer). Sections are written as chars(key) = int 0, int length, UTF-16 chars,
followed by the section's byte length. This finds every such key and names the innermost section that holds
the first differing byte (each further save is compared with the first).
"""
import re
import struct
import sys
import zlib


def raw(path):
    return zlib.decompress(open(path, "rb").read())[4:]


KEY = re.compile(rb"\x00\x00\x00\x00\x00\x00\x00([\x01-\x28])((?:\x00[A-Z0-9_ .$a-z]){1,40})")


def sections(b):
    out = []
    for m in KEY.finditer(b):
        n = m.group(1)[0]
        chars = m.group(2)
        if len(chars) < 2 * n:
            continue
        key = chars[: 2 * n].decode("utf-16-be")
        end = m.start() + 8 + 2 * n
        if end + 4 > len(b):
            continue
        size = struct.unpack(">i", b[end:end + 4])[0]
        if 0 <= size <= len(b) - end - 4:
            out.append((end + 4, end + 4 + size, key))
    return out


def main(paths):
    a = raw(paths[0])
    secs = sections(a)
    print("%s: %d bytes, %d sections" % (paths[0], len(a), len(secs)))
    for p in paths[1:]:
        b = raw(p)
        n = min(len(a), len(b))
        i = next((k for k in range(n) if a[k] != b[k]), None)
        if i is None:
            print("%s: identical%s" % (p, "" if len(a) == len(b) else " for %d bytes, lengths %d vs %d" % (n, len(a), len(b))))
            continue
        holding = [s for s in secs if s[0] <= i < s[1]]
        path = " > ".join(s[2] for s in sorted(holding, key=lambda s: s[0]))
        print("%s: first difference at byte %d (of %d) in %s" % (p, i, n, path or "?"))
        print("    ref: %s\n    run: %s" % (a[i:i + 24].hex(" "), b[i:i + 24].hex(" ")))
        diffs = sum(1 for k in range(n) if a[k] != b[k])
        print("    %d bytes differ in total" % diffs)


if __name__ == "__main__":
    main(sys.argv[1:])
