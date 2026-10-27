#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 AXML 解析器，用于自检 AndroidManifest.xml 二进制结构。"""
import struct
import sys

RES_IDS = {}


def parse(path):
    d = open(path, 'rb').read()
    pos = 0
    typ, hsz, size = struct.unpack_from('<HHI', d, 0)
    assert typ == 0x0003, hex(typ)
    pos = hsz
    pool = None
    attrs_map = []
    while pos < size:
        ctype, chsz, csize = struct.unpack_from('<HHI', d, pos)
        if ctype == 0x0001:
            cnt, styc, flags, sstart, stystart = struct.unpack_from('<IIIII', d, pos + 8)
            offs = struct.unpack_from('<%dI' % cnt, d, pos + chsz)
            base = pos + sstart
            strs = []
            for o in offs:
                p = base + o
                if flags & 0x100:  # UTF-8
                    n = d[p]
                    p += 2 if n & 0x80 else 1
                    n2 = d[p]
                    p += 2 if n2 & 0x80 else 1
                    strs.append(d[p:p + n2].decode('utf-8', 'replace'))
                else:
                    n = struct.unpack_from('<H', d, p)[0]
                    p += 2
                    strs.append(d[p:p + n * 2].decode('utf-16-le', 'replace'))
            pool = strs
        elif ctype == 0x0180:
            n = (csize - chsz) // 4
            attrs_map = list(struct.unpack_from('<%dI' % n, d, pos + chsz))
        elif ctype == 0x0102:
            ns, name, aStart, aSize, aCount, idi, cli, sti = struct.unpack_from('<IIHHHHHH', d, pos + 16)
            print('=' * 4, 'ELEMENT', pool[name], 'attrs=%d' % aCount)
            abase = pos + 16 + aStart
            for i in range(aCount):
                ans, aname, raw, vsz, res0, dtype, data = struct.unpack_from('<IIIHBBI', d, abase + i * aSize)
                rid = attrs_map[aname] if aname < len(attrs_map) else 0
                val = ('str:%r' % pool[data]) if dtype == 0x03 else ('int:%d' % data if dtype == 0x10 else 'bool:%d' % data)
                print('     %-16s rid=0x%08x ns=%s %s' % (
                    pool[aname], rid, '-' if ans == 0xFFFFFFFF else pool[ans], val))
        elif ctype == 0x0103:
            ns, name = struct.unpack_from('<II', d, pos + 16)
            print('    </%s>' % pool[name])
        pos += csize


if __name__ == '__main__':
    parse(sys.argv[1])
