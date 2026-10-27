#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 4 字节对齐的 ZIP（等价于 zipalign）。

Android 11+ 强制要求：resources.arsc 必须以 STORED（不压缩）方式存放，
且其数据起始偏移必须 4 字节对齐，否则安装时直接报
  Failed parse during installPackageLI: ... require ... aligned on a 4-byte boundary

Python 标准库的 zipfile 不支持对齐，所以这里手写最小实现：

  本地头(30) + 文件名 + extra(补位) + 数据
  补位用 0xD935（Android ZIP Alignment Extra Field）这个公开的 ID，
  系统见到未知 ID 会跳过，不影响解析。
"""

import struct
import sys
import zipfile
import zlib


ALIGN = 4
EXTRA_ID = 0xD935      # Android ZIP Alignment Extra Field


def dos_time(t):
    """把 (年,月,日,时,分,秒) 换成 DOS 时间戳，固定值即可"""
    return 0, 0x21   # 1980-01-01 00:00:00


def build_zip(entries, out_path):
    """
    entries: [(arcname, data_bytes, compress_bool), ...]
    """
    out = open(out_path, "wb")

    central = []
    offset = 0

    for arcname, data, compress in entries:
        name_b = arcname.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF

        if compress and len(data) > 0:
            comp = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = comp.compress(data) + comp.flush()
            method = 8
        else:
            payload = data
            method = 0

        # 计算补位：让数据起始偏移落在 4 字节边界上
        base = offset + 30 + len(name_b)
        pad = (ALIGN - base % ALIGN) % ALIGN
        if pad == 0:
            extra = b""
        else:
            # 一个合法的 extra 字段：id(2) + size(2) + data(size)
            extra = struct.pack("<HH", EXTRA_ID, pad) + b"\x00" * pad
        assert (offset + 30 + len(name_b) + len(extra)) % ALIGN == 0

        t, d = dos_time(None)

        local = struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50,     # 签名
            20,             # 解压所需版本
            0,              # 标志
            method,
            t, d,
            crc,
            len(payload),
            len(data),
            len(name_b),
            len(extra),
        )

        out.write(local)
        out.write(name_b)
        out.write(extra)
        out.write(payload)

        central.append((name_b, method, t, d, crc, len(payload), len(data),
                        len(extra), offset))
        offset += 30 + len(name_b) + len(extra) + len(payload)

    cd_start = offset
    for (name_b, method, t, d, crc, csize, usize, extralen, off) in central:
        hdr = struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50,
            20, 20,
            0, method,
            t, d,
            crc, csize, usize,
            len(name_b), extralen,
            0, 0, 0, 0,
            off,
        )
        out.write(hdr)
        out.write(name_b)
        # 中央目录里的 extra 长度必须与本地头一致
        if extralen:
            pad = extralen - 4
            out.write(struct.pack("<HH", EXTRA_ID, pad) + b"\x00" * pad)
        offset += len(hdr) + len(name_b) + extralen

    cd_size = offset - cd_start

    out.write(struct.pack(
        "<IHHHHIIH",
        0x06054B50,
        0, 0,
        len(central), len(central),
        cd_size, cd_start,
        0,
    ))
    out.close()
    return offset + 22


def main():
    src, dst = sys.argv[1], sys.argv[2]

    # 从普通 zip 里读出内容，再按对齐规则重写
    z = zipfile.ZipFile(src)
    entries = []
    for info in z.infolist():
        data = z.read(info.filename)
        # resources.arsc 与图标都不压缩：前者是系统硬要求，
        # 后者本身已是压缩过的 PNG，再压收益极小
        compress = not (info.filename.endswith(".arsc")
                        or info.filename.endswith(".png"))
        entries.append((info.filename, data, compress))
    z.close()

    n = build_zip(entries, dst)
    print("aligned zip -> %s (%d 字节)" % (dst, n))


if __name__ == "__main__":
    main()
