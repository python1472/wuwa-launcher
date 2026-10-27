#!/bin/sh
# 打离线源码包 —— 拿到就能断网构建
#
# 用法：
#   sh tools/pack_offline.sh              生成 wuwa-launcher-offline-<日期>.tar.gz
#   sh tools/pack_offline.sh 1.3          指定版本标签
#
# 与 GitHub 仓库的区别：
#   仓库走 .gitignore，42MB 构建依赖不进 git，clone 后要 fetch_deps.sh；
#   这个包把依赖一起装进去，给「没网 / 网慢 / 不想折腾」的人用。
#
# 包里刻意不含：
#   signing/keystore.p12   签名私钥不能外发（见 README 构建章节）
#   build/                 中间产物
#   *.apk                  构建产物，让人自己编
#   docs/design/.trash/    历史草稿

set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)

VER="$1"
[ -n "$VER" ] || VER=$(date +%Y%m%d)
NAME="wuwa-launcher-offline-$VER"
STAGE="/tmp/$NAME"

echo "==> 1/5 检查依赖是否完整"
if ! sh tools/fetch_deps.sh --check; then
  echo
  echo "依赖不全，先跑：sh tools/fetch_deps.sh"
  exit 1
fi

echo "==> 2/5 准备暂存目录"
rm -rf "$STAGE"
mkdir -p "$STAGE"

echo "==> 3/5 复制源码、文档、工具链"
# 源码与文档
for d in src docs; do
  [ -d "$d" ] && cp -R "$d" "$STAGE/"
done
for f in README.md CONTEXT.md build.sh .gitignore; do
  [ -f "$f" ] && cp "$f" "$STAGE/"
done

# 工程文件（图标设计稿等）
[ -d icon ] && cp -R icon "$STAGE/"

# 工具：脚本全带，大件只带二进制依赖
mkdir -p "$STAGE/tools"
for f in tools/*.py tools/*.sh tools/Sign.java tools/dump_axml.py; do
  [ -f "$f" ] && cp "$f" "$STAGE/tools/"
done
# 离线包的关键：这几个大件一起装进去
cp tools/android.jar tools/r8.jar tools/apksig.jar "$STAGE/tools/"
cp -R tools/shizuku "$STAGE/tools/shizuku"

# 去掉无用草稿
rm -rf "$STAGE/docs/design/.trash" 2>/dev/null || true

echo "==> 4/5 写离线说明"
cat > "$STAGE/OFFLINE.md" <<'EOF'
# 离线构建包

这个包已经把构建依赖一起装进来了，**不需要联网**。

## 构建

```sh
sh build.sh
```

产物：`鸣潮启动器.apk`

唯一要求：机器上有 `openjdk17`（提供 `javac` / `java` / `keytool`）
和 `python3`。不需要 Android SDK、Gradle、aapt2。

## 和 GitHub 仓库版本的差别

| | GitHub 仓库 | 本离线包 |
|---|---|---|
| 构建依赖 | 需先跑 `tools/fetch_deps.sh` 下载 42MB | 已包含 |
| 体积 | 约 2.5MB | 约 30MB |
| 构建 | `fetch_deps.sh` → `build.sh` | 直接 `build.sh` |

## 依赖校验

不确定依赖有没有损坏时：

```sh
sh tools/fetch_deps.sh --check
```

## 关于签名

包里**不含** `signing/keystore.p12`。首次运行 `build.sh` 会自动生成
一份新的签名密钥，所以你编出来的 APK **签名和官方发布版不同**：

- 不能覆盖安装官方版，会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
- 想装的话先卸载官方版（会清掉 App 内的设置）

这是有意的：签名私钥不外发。

## 依赖清单

| 文件 | 用途 |
|---|---|
| `tools/android.jar` | Android 34 SDK 桩，`javac` 编译时用 |
| `tools/r8.jar` | 内含 d8，把 `.class` 转成 `classes.dex` |
| `tools/apksig.jar` | APK 签名库 |
| `tools/shizuku/*/classes.jar` | Shizuku API / Provider / AIDL 接口 |

这些只在**编译时**用，不会进入 APK。最终 APK 约 250KB，自包含。
EOF

echo "==> 5/5 打包"
mkdir -p "$ROOT/dist"
cd /tmp
tar -czf "$ROOT/dist/$NAME.tar.gz" "$NAME"

cd "$ROOT"
SIZE=$(wc -c < "dist/$NAME.tar.gz" | tr -d ' ')
echo
echo "完成：${ROOT}/dist/${NAME}.tar.gz（$SIZE 字节）"
echo
echo "校验包内依赖："
tar -tzf "dist/$NAME.tar.gz" | grep -E 'tools/(android|r8|apksig)\.jar$' | sed 's/^/  /'
tar -tzf "dist/$NAME.tar.gz" | grep -E 'tools/shizuku/.*classes\.jar$' | sed 's/^/  /'
echo
echo "确认没有把签名私钥打进去："
if tar -tzf "dist/$NAME.tar.gz" | grep -q 'keystore'; then
  echo "  !! 警告：包内含 keystore，请不要外发"
else
  echo "  ✓ 无 keystore"
fi
