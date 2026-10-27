#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 AndroidManifest.xml 二进制 AXML。

属性表直接照抄 aapt 真实输出的 resmap（从 apktool 解析过的 APK 里提取），
保证属性 ID 与字符串池索引和 aapt 完全一致。
"""

import struct
import sys

# aapt 真实的属性名 -> 资源 ID（顺序即字符串池前缀顺序）
AAPT_ATTRS = [
    ("theme", 0x01010000),
    ("label", 0x01010001),
    ("icon", 0x01010002),
    ("name", 0x01010003),
    ("permission", 0x01010006),
    ("protectionLevel", 0x01010009),
    ("enabled", 0x0101000E),
    ("exported", 0x01010010),
    ("process", 0x01010011),
    ("taskAffinity", 0x01010012),
    ("multiprocess", 0x01010013),
    ("excludeFromRecents", 0x01010017),
    ("authorities", 0x01010018),
    ("grantUriPermissions", 0x0101001B),
    ("launchMode", 0x0101001D),
    ("value", 0x01010024),
    ("resource", 0x01010025),
    ("mimeType", 0x01010026),
    ("scheme", 0x01010027),
    ("host", 0x01010028),
    ("port", 0x01010029),
    ("path", 0x0101002A),
    ("targetActivity", 0x01010202),
    ("minSdkVersion", 0x0101020C),
    ("versionCode", 0x0101021B),
    ("versionName", 0x0101021C),
    ("windowSoftInputMode", 0x0101022B),
    ("targetSdkVersion", 0x01010270),
    ("maxSdkVersion", 0x01010271),
    ("allowBackup", 0x01010280),
    ("required", 0x0101028E),
    ("largeHeap", 0x0101035A),
    ("supportsRtl", 0x010103AF),
    ("extractNativeLibs", 0x010104EA),
    ("usesCleartextTraffic", 0x010104EC),
    ("directBootAware", 0x01010505),
    ("networkSecurityConfig", 0x01010527),
    ("roundIcon", 0x0101052C),
    ("compileSdkVersion", 0x01010572),
    ("compileSdkVersionCodename", 0x01010573),
    ("appComponentFactory", 0x0101057A),
    ("foregroundServiceType", 0x01010599),
    ("localeConfig", 0x0101065B),
    # aapt 那份里没有、但我们要用的
    ("configChanges", 0x0101001F),
]
AAPT_ATTRS.sort(key=lambda x: x[1])
ATTR = {name: i for i, (name, _rid) in enumerate(AAPT_ATTRS)}

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12
NO_ENTRY = 0xFFFFFFFF


def u16(v):
    return struct.pack("<H", v)


def u32(v):
    return struct.pack("<I", v & 0xFFFFFFFF)


class StrPool(object):
    def __init__(self):
        self.strings = []
        self.index = {}

    def add(self, s):
        if s in self.index:
            return self.index[s]
        i = len(self.strings)
        self.strings.append(s)
        self.index[s] = i
        return i

    def build(self):
        offsets = []
        data = b""
        for s in self.strings:
            offsets.append(len(data))
            data += u16(len(s)) + s.encode("utf-16-le") + b"\x00\x00"
        while len(data) % 4:
            data += b"\x00"
        count = len(self.strings)
        header_size = 28
        strings_start = header_size + 4 * count
        body = b"".join(u32(o) for o in offsets) + data
        size = header_size + len(body)
        head = (u16(0x0001) + u16(header_size) + u32(size) + u32(count) +
                u32(0) + u32(0) + u32(strings_start) + u32(0))
        return head + body


def node_header(chunk_type, size, line=1):
    return u16(chunk_type) + u16(16) + u32(size) + u32(line) + u32(NO_ENTRY)


def start_element(name_idx, attrs):
    attrs = sorted(attrs, key=lambda a: a[1])
    blob = b""
    for (ns, nm, raw, dtype, data) in attrs:
        blob += u32(ns) + u32(nm) + u32(raw)
        blob += u16(8) + b"\x00" + bytes([dtype]) + u32(data)
    size = 16 + 20 + 20 * len(attrs)
    body = (u32(NO_ENTRY) + u32(name_idx) + u16(20) + u16(20) +
            u16(len(attrs)) + u16(0) + u16(0) + u16(0))
    return node_header(0x0102, size) + body + blob


def end_element(name_idx):
    return node_header(0x0103, 24) + u32(NO_ENTRY) + u32(name_idx)


def ns_node(chunk_type, prefix_idx, uri_idx):
    return node_header(chunk_type, 24) + u32(prefix_idx) + u32(uri_idx)


def build_manifest(package, label, version_code, version_name,
                   min_sdk, target_sdk, activity_class, config_changes,
                   extra_queries):
    pool = StrPool()
    for aname, _rid in AAPT_ATTRS:
        pool.add(aname)

    S_ANDROID = pool.add("android")
    S_URI = pool.add("http://schemas.android.com/apk/res/android")
    S_MANIFEST = pool.add("manifest")
    S_PACKAGE_KW = pool.add("package")
    S_USES_PERM = pool.add("uses-permission")
    S_USES_SDK = pool.add("uses-sdk")
    S_QUERIES = pool.add("queries")
    S_PACKAGE_TAG = pool.add("package")
    S_APPLICATION = pool.add("application")
    S_META = pool.add("meta-data")
    S_PROVIDER = pool.add("provider")
    S_ACTIVITY = pool.add("activity")
    S_SERVICE = pool.add("service")
    S_RECEIVER = pool.add("receiver")
    S_INTENT = pool.add("intent-filter")
    S_ACTION = pool.add("action")
    S_CATEGORY = pool.add("category")

    NS = NO_ENTRY
    A = S_URI

    def s_attr(name, value):
        i = pool.add(value)
        return (A, ATTR[name], i, TYPE_STRING, i)

    def i_attr(name, value):
        return (A, ATTR[name], NO_ENTRY, TYPE_INT_DEC, value)

    def r_attr(name, res_id):
        """资源引用型属性，例如 android:icon="@mipmap/ic_launcher" """
        return (A, ATTR[name], NO_ENTRY, TYPE_REFERENCE, res_id)

    def b_attr(name, value):
        return (A, ATTR[name], NO_ENTRY, TYPE_INT_BOOLEAN,
                0xFFFFFFFF if value else 0)

    resmap = b"".join(u32(rid) for _n, rid in AAPT_ATTRS)
    resmap_chunk = u16(0x0180) + u16(8) + u32(8 + len(resmap)) + resmap

    chunks = [ns_node(0x0100, S_ANDROID, S_URI)]

    pkg_idx = pool.add(package)
    chunks.append(start_element(S_MANIFEST, [
        (NS, S_PACKAGE_KW, pkg_idx, TYPE_STRING, pkg_idx),
        i_attr("versionCode", version_code),
        s_attr("versionName", version_name),
    ]))

    # 只需要 shell 一次性授予的两个权限（不用 root）
    for perm in ("android.permission.WRITE_SECURE_SETTINGS",
                 "android.permission.WRITE_SETTINGS",
                 "android.permission.SYSTEM_ALERT_WINDOW",
                 "android.permission.POST_PROMOTED_NOTIFICATIONS",
                 # OPPO/realme 私有的普通权限，声明即可拿到。
                 # 有了它才能打开系统的「流体云」设置页
                 "com.oplus.notificationmanager.FLUID_SEEDING_SETTINGS",
                 "android.permission.POST_NOTIFICATIONS",
                 "android.permission.FOREGROUND_SERVICE",
                 "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                 "moe.shizuku.manager.permission.API_V23"):
        chunks.append(start_element(S_USES_PERM, [s_attr("name", perm)]))
        chunks.append(end_element(S_USES_PERM))

    chunks.append(start_element(S_USES_SDK, [
        i_attr("minSdkVersion", min_sdk),
        i_attr("targetSdkVersion", target_sdk),
    ]))
    chunks.append(end_element(S_USES_SDK))

    # 包可见性：Android 11+ 不加这个就 getLaunchIntentForPackage 不到鸣潮
    chunks.append(start_element(S_QUERIES, []))
    for q in extra_queries:
        chunks.append(start_element(S_PACKAGE_TAG, [s_attr("name", q)]))
        chunks.append(end_element(S_PACKAGE_TAG))
    chunks.append(end_element(S_QUERIES))

    # 应用图标：引用 resources.arsc 里的 mipmap/ic_launcher（0x7f010000）
    ICON_REF = 0x7F010000
    chunks.append(start_element(S_APPLICATION, [
        s_attr("label", label),
        r_attr("icon", ICON_REF),
        r_attr("roundIcon", ICON_REF),
    ]))

    # Shizuku：靠它拿 shell(uid 2000) 权限执行 wm，免 root
    chunks.append(start_element(S_META, [
        s_attr("name", "moe.shizuku.client.V3_SUPPORT"),
        s_attr("value", "true"),
    ]))
    chunks.append(end_element(S_META))
    chunks.append(start_element(S_PROVIDER, [
        s_attr("name", "com.miaojian.wuwalauncher.BinderProvider"),
        s_attr("authorities", package + ".shizuku"),
        b_attr("multiprocess", False),
        b_attr("enabled", True),
        b_attr("exported", True),
        s_attr("permission", "android.permission.INTERACT_ACROSS_USERS_FULL"),
    ]))
    chunks.append(end_element(S_PROVIDER))

    chunks.append(start_element(S_ACTIVITY, [
        s_attr("name", activity_class),
        b_attr("exported", True),
        i_attr("configChanges", config_changes),
    ]))
    chunks.append(start_element(S_INTENT, []))
    chunks.append(start_element(S_ACTION, [s_attr("name", "android.intent.action.MAIN")]))
    chunks.append(end_element(S_ACTION))
    chunks.append(start_element(S_CATEGORY,
                                [s_attr("name", "android.intent.category.LAUNCHER")]))
    chunks.append(end_element(S_CATEGORY))
    chunks.append(end_element(S_INTENT))
    chunks.append(end_element(S_ACTIVITY))

    # 守护服务：任务被划掉时还原显示
    chunks.append(start_element(S_SERVICE, [
        s_attr("name", "com.miaojian.wuwalauncher.GuardService"),
        b_attr("exported", False),
        # FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 << 30
        i_attr("foregroundServiceType", 0x40000000),
    ]))
    chunks.append(end_element(S_SERVICE))

    # 通知里「还原」按钮的接收器
    chunks.append(start_element(S_RECEIVER, [
        s_attr("name", "com.miaojian.wuwalauncher.RestoreActionReceiver"),
        b_attr("exported", False),
    ]))
    chunks.append(end_element(S_RECEIVER))

    chunks.append(end_element(S_APPLICATION))
    chunks.append(end_element(S_MANIFEST))
    chunks.append(ns_node(0x0101, S_ANDROID, S_URI))

    pool_chunk = pool.build()
    body = b"".join(chunks)
    total = 8 + len(pool_chunk) + len(resmap_chunk) + len(body)
    return u16(0x0003) + u16(8) + u32(total) + pool_chunk + resmap_chunk + body


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "AndroidManifest.xml"
    data = build_manifest(
        package="com.miaojian.wuwalauncher",
        label="\u9e23\u6f6e\u542f\u52a8\u5668",
        version_code=4,
        version_name="1.3",
        min_sdk=24,
        target_sdk=34,
        activity_class="com.miaojian.wuwalauncher.LauncherActivity",
        config_changes=0x40003FA4,
        extra_queries=["com.kurogame.mingchao"],
    )
    with open(out, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))


if __name__ == "__main__":
    main()
