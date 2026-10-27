#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""创建 GitHub Release 并上传 APK 附件。token 从 /root/.gh_token 读，不经过命令行。"""

import json
import os
import urllib.request
import urllib.parse

OWNER = "python1472"
REPO = "wuwa-launcher"
TAG = "v1.3"
APK = "dist/鸣潮启动器.apk"
ASSET_NAME = "wuwa-launcher-v1.3.apk"

TOKEN = open("/root/.gh_token").read().strip()


def api(method, path, body=None, raw=None, ctype=None, host=None):
    url = (host or "https://api.github.com") + path
    data = None
    if body is not None:
        data = json.dumps(body).encode()
    elif raw is not None:
        data = raw
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if ctype:
        req.add_header("Content-Type", ctype)
    elif body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


NOTES = """### 鸣潮启动器 v1.3

给《鸣潮》Android 端调渲染分辨率与屏幕方向的辅助工具。**免 Root**，
通过 Shizuku 获取 shell 身份，不需要无障碍服务或 Xposed。

#### 三个档位

| 档位 | 含义 | 需要 |
|---|---|---|
| 原生 | 完全不干预，游戏按自己默认跑 | — |
| 高清 | 1×1 透明悬浮窗矫正方向，让游戏按屏幕物理分辨率渲染 | 悬浮窗权限 |
| 极限 / 自定义 | 在矫正方向基础上抬倍率（1.0× ~ 3.0×，0.1 一档） | 悬浮窗 + Shizuku |

#### 主要能力

- **提升渲染分辨率**：`wm size` + `wm density` 按屏幕物理尺寸自动算档位，密度同比例缩放
- **方向矫正**：1×1 透明悬浮窗把 `SCREEN_ORIENTATION_SENSOR` 压住游戏自己声明的横屏
- **退出自动还原**：划掉任务、点还原按钮、进程被杀后下次打开补还原
- **高分辨率下切三键导航**：全面屏手势区域在高分辨率下会失效

#### 安装

下载下方 `wuwa-launcher-v1.3.apk`，安装后需要：

1. **Shizuku 已启动并授权**（改分辨率必需）
2. **悬浮窗权限**（方向控制必需）
3. **修改系统设置权限**（锁旋转用，缺了退出后方向可能不还原）

App 内会引导申请，无需手动执行 adb 命令。

#### 兼容性

- 最低 Android 7.0（API 24）
- 已在 realme RMX6688 / ColorOS 16（Android 16）实测
- 档位按屏幕分辨率与像素总量现算，不写死，2K 屏与 1080p 屏都能用

#### 校验

```
MD5     11033184d959ee1d98d663a2e6277c8a
大小    255,805 字节
```

#### 注意

- 3.0× 属于极限档位，崩溃后屏幕可能停留在超高分辨率且重启不清除，
  恢复需要外部执行 `wm size reset`
- 本项目全程由 DeepSeek V4.1 Flash 制作
"""

print("==> 1/3 创建 release")
status, rel = api("POST", "/repos/%s/%s/releases" % (OWNER, REPO), {
    "tag_name": TAG,
    "target_commitish": "main",
    "name": "鸣潮启动器 v1.3",
    "body": NOTES,
    "draft": False,
    "prerelease": False,
})
if status not in (200, 201):
    print("   失败 %d: %s" % (status, rel.get("message")))
    raise SystemExit(1)
rid = rel["id"]
print("   成功：%s" % rel["html_url"])
print("   tag: %s" % rel["tag_name"])

print("==> 2/3 上传 APK")
data = open(APK, "rb").read()
url = "/repos/%s/%s/releases/%d/assets?name=%s" % (
    OWNER, REPO, rid, urllib.parse.quote(ASSET_NAME))
status, asset = api("POST", url, raw=data,
                    ctype="application/vnd.android.package-archive",
                    host="https://uploads.github.com")
if status not in (200, 201):
    print("   失败 %d: %s" % (status, asset.get("message")))
    raise SystemExit(1)
print("   %s  %.1f KB" % (asset["name"], asset["size"] / 1024))
print("   %s" % asset["browser_download_url"])

print("==> 3/3 回读验证")
status, check = api("GET", "/repos/%s/%s/releases/tags/%s" % (OWNER, REPO, TAG))
if status == 200:
    print("   标题: %s" % check["name"])
    print("   状态: %s" % ("草稿" if check["draft"] else "已发布"))
    print("   附件:")
    for a in check["assets"]:
        print("     - %s (%d 字节)" % (a["name"], a["size"]))
    print("   页面: %s" % check["html_url"])
else:
    print("   回读失败 %d" % status)
