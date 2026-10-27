#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 adaptive-icon 的 XML 编译成二进制 AXML。

格式和 AndroidManifest.xml 一样，只是元素名与属性不同。
这里只需要处理一个固定结构：

    <adaptive-icon xmlns:android="...">
        <background android:drawable="@mipmap/ic_launcher_background"/>
        <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    </adaptive-icon>
"""

import struct
import sys

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
NO_ENTRY = 0xFFFFFFFF

# aapt 的属性 ID
ATTR_DRAWABLE = 0x01010199

# adaptive-icon 的资源属性没有资源 ID（是元素名，不是 attribute），
# 所以用字符串表示，name 指向字符串池，attr id = NO_ENTRY


def u32(v):
    return struct.pack("<I", v)


def u16(v):
    return struct.pack("<H", v)


def string_pool(strings):
    offsets = []
    data = b""
    for s in strings:
        offsets.append(len(data))
        b = s.encode("utf-8")
        n = len(s)
        data += bytes([n]) if n <= 0x7F else bytes([0x80 | (n >> 8), n & 0xFF])
        data += bytes([len(b)]) if len(b) <= 0x7F else bytes([0x80 | (len(b) >> 8), len(b) & 0xFF])
        data += b + b"\x00"

    while len(data) % 4:
        data += b"\x00"

    header_size = 28
    strings_start = header_size + 4 * len(strings)

    body = struct.pack("<IIIII", len(strings), 0, 1 << 8, strings_start, 0)
    body += b"".join(u32(o) for o in offsets)
    body += data

    return struct.pack("<HHI", 0x0001, header_size, 8 + len(body)) + body


def start_element(name_idx, attrs):
    """attrs: [(ns, name_idx, raw_value, data_type, data), ...]"""
    blob = b""
    for (ns, nm, raw, dtype, data) in sorted(attrs, key=lambda a: a[1]):
        blob += u32(ns) + u32(nm) + u32(raw)
        blob += u16(8) + b"\x00" + bytes([dtype]) + u32(data)

    size = 16 + 20 + 20 * len(attrs)
    body = (u32(NO_ENTRY) + u32(name_idx) + u16(20) + u16(20) +
            u16(len(attrs)) + u16(0) + u16(0) + u16(0))
    return struct.pack("<HHI", 0x0102, size, size) + body + blob


def end_element(name_idx):
    return struct.pack("<HHI", 0x0103, 24, 24) + u32(NO_ENTRY) + u32(name_idx)


def ns_node(prefix_idx, uri_idx):
    return struct.pack("<HHI", 0x0100, 24, 24) + u32(prefix_idx) + u32(uri_idx)


def build(out_path,
          bg_ref=0x7F010001,
          fg_ref=0x7F010002):
    # 字符串池顺序很重要：先放属性名（如果有），再放其它
    strings = [
        "drawable",              # 0  属性名
        "android",               # 1  前缀
        "http://schemas.android.com/apk/res/android",   # 2
        "adaptive-icon",         # 3
        "background",            # 4
        "foreground",            # 5
    ]
    S = {s: i for i, s in enumerate(strings)}

    pool = string_pool(strings)
    A = S["http://schemas.android.com/apk/res/android"]

    chunks = [pool]

    chunks.append(ns_node(S["android"], S["http://schemas.android.com/apk/res/android"]))

    chunks.append(start_element(S["adaptive-icon"], []))

    # background
    chunks.append(start_element(S["background"], [
        (A, 0, NO_ENTRY, TYPE_REFERENCE, bg_ref),
    ]))
    chunks.append(end_element(S["background"]))

    # foreground
    chunks.append(start_element(S["foreground"], [
        (A, 0, NO_ENTRY, TYPE_REFERENCE, fg_ref),
    ]))
    chunks.append(end_element(S["foreground"]))

    chunks.append(end_element(S["adaptive-icon"]))

    chunks.append(ns_node(NO_ENTRY, NO_ENTRY))

    body = b"".join(chunks)
    xml = struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body

    with open(out_path, "wb") as f:
        f.write(xml)
    return len(xml)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "ic_launcher.xml"
    print("wrote %s (%d 字节)" % (out, build(out)))
