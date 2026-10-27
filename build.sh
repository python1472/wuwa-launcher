#!/bin/sh
# 鸣潮启动器 —— 一键构建
#
# 环境要求：openjdk17（javac / keytool）
# 依赖：tools/ 下的 android.jar、r8.jar、apksig.jar、
#       shizuku/{api,provider,aidl}/classes.jar
#
# 用法：sh build.sh

set -e
cd "$(dirname "$0")"

SRC=src/com/miaojian/wuwalauncher
OUT=build
APK_NAME="dist/鸣潮启动器.apk"

LIBS=tools/android.jar:tools/shizuku/api/classes.jar:tools/shizuku/provider/classes.jar:tools/shizuku/aidl/classes.jar

echo "==> 0/6 检查构建依赖"
# 依赖不全就别往下走。否则会在第 5 步 d8 处报
# "Could not find or load main class"，隔了 4 个步骤才暴露真因。
miss=""
for f in tools/android.jar tools/r8.jar tools/apksig.jar \
         tools/shizuku/api/classes.jar tools/shizuku/provider/classes.jar \
         tools/shizuku/aidl/classes.jar; do
  [ -f "$f" ] || miss="$miss $f"
done
if [ -n "$miss" ]; then
  echo "缺少依赖：$miss"
  echo "请先运行：sh tools/fetch_deps.sh"
  exit 1
fi
# r8.jar 特别容易因为下载地址失效拿到错误页，单独验一次能不能识别成 jar
if ! unzip -l tools/r8.jar >/dev/null 2>&1; then
  echo "tools/r8.jar 不是有效的 jar（可能是下载中断或错误页）"
  echo "删除后重新运行：sh tools/fetch_deps.sh"
  exit 1
fi
echo "  依赖完整"

echo "==> 1/6 清理"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk" "$OUT/lib" dist

if [ ! -f "$OUT/../signing/keystore.p12" ]; then
  echo "==> 2/6 生成签名密钥（首次运行）"
  mkdir -p signing
  keytool -genkeypair -keystore signing/keystore.p12 -storetype PKCS12 \
    -alias wuwa -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass wuwa123456 -keypass wuwa123456 \
    -dname "CN=Wuwa Launcher, O=Miao, C=CN" >/dev/null 2>&1
else
  echo "==> 2/6 复用已有签名密钥"
fi

echo "==> 3/6 生成 AndroidManifest.xml"
python3 tools/mkmanifest.py "$OUT/apk/AndroidManifest.xml"

echo "==> 4/6 编译 Java"
javac -nowarn -source 8 -target 8 -cp "$LIBS" -d "$OUT/classes" "$SRC"/*.java

echo "==> 5/6 转换为 dex"
cp tools/shizuku/api/classes.jar "$OUT/lib/api.jar"
cp tools/shizuku/provider/classes.jar "$OUT/lib/provider.jar"
cp tools/shizuku/aidl/classes.jar "$OUT/lib/aidl.jar"
cd "$OUT/lib"
unzip -o -q api.jar && unzip -o -q provider.jar && unzip -o -q aidl.jar
cd ../..
java -cp tools/r8.jar com.android.tools.r8.D8 \
  --min-api 24 --lib tools/android.jar \
  --output "$OUT/dex" \
  $(find "$OUT/classes" "$OUT/lib" -name '*.class') 2>&1 \
  | grep -viE "warning in|Runnable|^$" || true

echo "==> 生成 resources.arsc"
mkdir -p "$OUT/apk/res/mipmap-anydpi-v26"
python3 tools/mkadaptive.py "$OUT/apk/res/mipmap-anydpi-v26/ic_launcher.xml"
python3 tools/mkresources.py "$OUT/apk/resources.arsc"

echo "==> 6/6 打包并签名"
python3 - "$OUT" <<'PY'
import sys, zipfile, os
out = sys.argv[1]
apk = os.path.join(out, "apk", "unsigned.apk")
# 脚本用 python3 - 从 stdin 执行，__file__ 不可靠，直接用当前工作目录
base = os.getcwd()

z = zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED)
z.write(os.path.join(out, "apk", "AndroidManifest.xml"), "AndroidManifest.xml")
z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")

# resources.arsc 必须不压缩（系统用 mmap 直接读）
z.write(os.path.join(out, "apk", "resources.arsc"), "resources.arsc",
        compress_type=zipfile.ZIP_STORED)

# res/ 下所有图标
# 先放编译好的自适应图标 XML
z.write(os.path.join(out, "apk", "res", "mipmap-anydpi-v26", "ic_launcher.xml"),
        "res/mipmap-anydpi-v26/ic_launcher.xml")

res_dir = os.path.join(base, "src", "res")
for root, _dirs, files in os.walk(res_dir):
    for f in files:
        full = os.path.join(root, f)
        rel = os.path.relpath(full, os.path.join(base, "src"))
        z.write(full, rel)
z.close()
PY

# 重打包成 4 字节对齐（Android 11+ 对 resources.arsc 的硬要求）
python3 tools/mkzip.py "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

javac -nowarn -cp tools/apksig.jar -d "$OUT/signer" tools/Sign.java
java -cp tools/apksig.jar:"$OUT/signer" Sign \
  "$OUT/apk/aligned.apk" "$APK_NAME" \
  signing/keystore.p12 wuwa wuwa123456

echo
echo "构建完成：$(pwd)/$APK_NAME"
ls -lh "$APK_NAME"
