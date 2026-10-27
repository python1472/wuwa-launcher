#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成最小的 resources.arsc（含自适应图标）。

沙箱里没有 aapt2（官方只发布 x86_64 linux 版），所以自己按
AOSP ResourceTypes.h 的二进制格式拼一份。

资源布局：
    mipmap/ic_launcher             0x7f010000
        ├ mdpi..xxxhdpi  -> res/mipmap-<密度>/ic_launcher.png      (旧系统)
        └ anydpi-v26     -> res/mipmap-anydpi-v26/ic_launcher.xml  (自适应)
    mipmap/ic_launcher_background  0x7f010001  -> res/mipmap-<密度>/ic_launcher_background.png
    mipmap/ic_launcher_foreground  0x7f010002  -> res/mipmap-<密度>/ic_launcher_foreground.png
"""

import os
import struct
import sys

RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202

TYPE_STRING = 0x03

PACKAGE_ID = 0x7F
TYPE_MIPMAP = 0x01

DENSITIES = [
    ("mdpi", 160),
    ("hdpi", 240),
    ("xhdpi", 320),
    ("xxhdpi", 480),
    ("xxxhdpi", 640),
]

# 每个资源条目：键名 -> [(配置名, 路径), ...]
ENTRIES = [
    ("ic_launcher",
     [("res/mipmap-%s/ic_launcher.png" % n, (dpi, 0)) for n, dpi in DENSITIES]
     + [("res/mipmap-anydpi-v26/ic_launcher.xml", (0, 26))]),

    ("ic_launcher_background",
     [("res/mipmap-%s/ic_launcher_background.png" % n, (dpi, 0)) for n, dpi in DENSITIES]),

    ("ic_launcher_foreground",
     [("res/mipmap-%s/ic_launcher_foreground.png" % n, (dpi, 0)) for n, dpi in DENSITIES]),
]


def pad4(data):
    n = len(data) % 4
    return data + b"\x00" * ((4 - n) % 4)


def chunk(chunk_type, header_size, body):
    size = 8 + len(body)
    return struct.pack("<HHI", chunk_type, header_size, size) + body


def string_pool(strings, utf8=True):
    """构建 ResStringPool（只处理 UTF-8 分支）"""
    offsets = []
    data = b""
    for s in strings:
        offsets.append(len(data))
        b = s.encode("utf-8")
        # UTF-8 模式：字符数 + 字节数，都用 UTF-16 长度的变长编码
        n_chars = len(s)
        if n_chars > 0x7F:
            data += bytes([0x80 | (n_chars >> 8), n_chars & 0xFF])
        else:
            data += bytes([n_chars])
        if len(b) > 0x7F:
            data += bytes([0x80 | (len(b) >> 8), len(b) & 0xFF])
        else:
            data += bytes([len(b)])
        data += b + b"\x00"

    data = pad4(data)
    strings_start = 28 + 4 * len(strings)
    header_size = 28

    body = struct.pack("<IIIII", len(strings), 0, UTF8_FLAG, strings_start, 0)
    body += b"".join(struct.pack("<I", o) for o in offsets)
    body += data

    return chunk(RES_STRING_POOL_TYPE, header_size, body)


UTF8_FLAG = 1 << 8


def res_config(density, sdk_version):
    """ResTable_config，28 字节基础结构"""
    return (struct.pack("<I", 28)
            + b"\x00" * 10                 # mcc/mnc/language/country/orient/touch
            + struct.pack("<H", density)
            + b"\x00" * 8                  # keyboard..inputPad

            + struct.pack("<HH", 0, 0)     # screenWidth / screenHeight
            + struct.pack("<HH", sdk_version, 0))


def type_spec(type_id, entry_count):
    body = struct.pack("<BBHI", type_id, 0, 0, entry_count)
    body += struct.pack("<I", 0) * entry_count
    return chunk(RES_TABLE_TYPE_SPEC_TYPE, 16, body)


def type_chunk(type_id, cfg, offsets_map, entries_blob):
    """一个配置下的数据块。

    offsets_map: {条目索引: 相对 entries_start 的偏移}
    entries_blob: 所有条目数据拼接
    """
    config = res_config(*cfg)
    header_size = 20 + len(config)
    max_idx = max(offsets_map) if offsets_map else 0
    count = max_idx + 1

    offsets = b""
    for i in range(count):
        offsets += struct.pack("<I", offsets_map.get(i, 0xFFFFFFFF))

    entries_start = header_size + len(offsets)

    body = struct.pack("<BBHII", type_id, 0, 0, count, entries_start)
    body += config
    body += offsets
    body += entries_blob

    return chunk(RES_TABLE_TYPE_TYPE, header_size, body)


def build_arsc(out_path):
    # ---------- 全局字符串池 ----------
    all_paths = []
    for _key, variants in ENTRIES:
        for path, _cfg in variants:
            if path not in all_paths:
                all_paths.append(path)
    global_pool = string_pool(all_paths)

    key_names_list = [e[0] for e in ENTRIES]
    type_names = string_pool(["mipmap"])
    key_names = string_pool(key_names_list)

    PKG_HEADER_SIZE = 288
    type_strings_off = PKG_HEADER_SIZE
    key_strings_off = PKG_HEADER_SIZE + len(type_names)

    # ---------- 按配置分组 ----------
    # configs: (density, sdk) -> {entry_index: path}
    configs = {}
    for idx, (_key, variants) in enumerate(ENTRIES):
        for path, cfg in variants:
            configs.setdefault(cfg, {})[idx] = path

    # ---------- 依次生成 type chunk ----------
    type_chunks = b""
    for cfg in sorted(configs.keys()):
        mapping = configs[cfg]
        blob = b""
        offs = {}
        for idx, path in sorted(mapping.items()):
            offs[idx] = len(blob)
            str_idx = all_paths.index(path)
            entry = struct.pack("<HHI", 8, 0, idx)          # ResTable_entry
            entry += struct.pack("<HBBI", 8, 0, TYPE_STRING, str_idx)
            blob += entry
        type_chunks += type_chunk(TYPE_MIPMAP, cfg, offs, blob)

    spec = type_spec(TYPE_MIPMAP, len(ENTRIES))

    pkg_body = struct.pack("<I", PACKAGE_ID)
    pkg_body += "com.miaojian.wuwalauncher".encode("utf-16-le").ljust(256, b"\x00")
    pkg_body += struct.pack("<IIIII", type_strings_off, 1, key_strings_off, len(key_names_list), 0)
    assert len(pkg_body) == PKG_HEADER_SIZE - 8, len(pkg_body)

    pkg_body += type_names + key_names + spec + type_chunks
    package = chunk(RES_TABLE_PACKAGE_TYPE, PKG_HEADER_SIZE, pkg_body)

    table_header = struct.pack("<I", 1)
    table = chunk(RES_TABLE_TYPE, 12, table_header + global_pool + package)

    with open(out_path, "wb") as f:
        f.write(table)
    return len(table)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "resources.arsc"
    size = build_arsc(out)
    print("wrote %s (%d 字节, %d 个资源)" % (out, size, len(ENTRIES)))
