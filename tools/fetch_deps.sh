#!/bin/sh
# 下载构建所需的大体积依赖（不进 git，也不进备份包）
#
# 用法：
#   sh tools/fetch_deps.sh             正常下载
#   sh tools/fetch_deps.sh --mirror CN 国内镜像优先
#   sh tools/fetch_deps.sh --check     只检查现有文件是否完整，不下载
#
# 两个设计要点：
#
# 1. curl 必须带 -f。
#    不带 -f 时，404 也返回 0，会把 189 字节的 XML 错误页存成 jar，
#    等到 4 步之后的 d8 才报 "Could not find or load main class" ——
#    报错点和真实原因隔得太远，极难排查。加 -f 后当场失败。
#
# 2. 下完必须验大小。
#    服务器返回 200 也可能拿到不完整的内容（断流、代理截断）。
#    这里给每个文件一个下限，低于下限就当失败并删掉，避免脏文件留坑。

set -e
cd "$(dirname "$0")"

MODE=""
case "$1" in
  --check)   MODE="check" ;;
  --mirror)  MODE="cn" ;;
  "")        MODE="" ;;
  *)         echo "未知参数：$1"; exit 2 ;;
esac

# 目标文件 / 最小合理字节数 / 说明
need_android=20000000     # 实际约 25MB
need_r8=10000000          # 实际约 16MB
need_apksig=300000        # 实际约 476KB

fail() {
  echo "!! $1" >&2
  exit 1
}

# 校验一个文件是否达到最小字节数
check_size() {
  f="$1"; min="$2"
  [ -f "$f" ] || return 1
  s=$(wc -c < "$f" | tr -d ' ')
  [ "$s" -ge "$min" ]
}

# 带校验的下载：$1=url  $2=输出  $3=最小字节  $4=说明
fetch() {
  url="$1"; out="$2"; min="$3"; desc="$4"
  echo "  下载 $desc ..."
  tmp="$out.part"
  rm -f "$tmp"
  if ! curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
        -o "$tmp" "$url"; then
    rm -f "$tmp"
    echo "    × 下载失败：$url"
    return 1
  fi
  if ! check_size "$tmp" "$min"; then
    s=$(wc -c < "$tmp" | tr -d ' ')
    rm -f "$tmp"
    echo "    × 内容不完整（$s 字节，至少应 $min）：疑似返回了错误页"
    return 1
  fi
  mv "$tmp" "$out"
  s=$(wc -c < "$out" | tr -d ' ')
  echo "    ✓ $out（$s 字节）"
  return 0
}

# ---- 只检查模式 ----
if [ "$MODE" = "check" ]; then
  echo "检查构建依赖完整性："
  ok=1
  if check_size android.jar $need_android; then
    echo "  ✓ android.jar       $(wc -c < android.jar | tr -d ' ') 字节"
  else
    echo "  × android.jar       缺失或过小"; ok=0
  fi
  if check_size r8.jar $need_r8; then
    echo "  ✓ r8.jar            $(wc -c < r8.jar | tr -d ' ') 字节"
  else
    echo "  × r8.jar            缺失或过小"; ok=0
  fi
  if check_size apksig.jar $need_apksig; then
    echo "  ✓ apksig.jar        $(wc -c < apksig.jar | tr -d ' ') 字节"
  else
    echo "  × apksig.jar        缺失或过小"; ok=0
  fi
  miss=0
  for a in api provider aidl; do
    if [ -f "shizuku/$a/classes.jar" ]; then
      echo "  ✓ shizuku/$a/classes.jar"
    else
      echo "  × shizuku/$a/classes.jar"; ok=0; miss=1
    fi
  done
  if [ $ok -eq 1 ]; then
    echo "依赖完整，可以构建。"
    exit 0
  else
    echo "依赖不完整，请运行 sh tools/fetch_deps.sh"
    exit 1
  fi
fi

# ---- android.jar ----
if ! check_size android.jar $need_android; then
  rm -f android.jar
  # 官方源 + 国内镜像
  if [ "$MODE" = "cn" ]; then
    urls="https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip
https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
  else
    urls="https://dl.google.com/android/repository/platform-34-ext7_r03.zip
https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip"
  fi
  got=0
  for u in $urls; do
    if fetch "$u" platform34.zip 50000000 "android.jar (API 34)"; then
      got=1
      break
    fi
  done
  [ $got -eq 1 ] || fail "android.jar 下载失败，请检查网络或手动下载放入 tools/"

  unzip -o -q platform34.zip android-34/android.jar
  [ -f android.jar ] || fail "platform-34 解压后没找到 android.jar"
  check_size android.jar $need_android || fail "解压出的 android.jar 异常"
  rm -f platform34.zip
  echo "  ✓ android.jar $(wc -c < android.jar | tr -d ' ') 字节"
fi

# ---- r8.jar ----
# 注意：旧的 storage.googleapis.com/r8-releases/raw/main/r8.jar 已 404，
# 不能再用。Google Maven 上的编号版本是稳定的，这里钉死 8.3.37。
if ! check_size r8.jar $need_r8; then
  rm -f r8.jar
  if ! fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar" \
             r8.jar $need_r8 "r8.jar (8.3.37 / d8)"; then
    echo "  改用国内镜像重试..."
    fetch "https://maven.aliyun.com/repository/google/com/android/tools/r8/8.3.37/r8-8.3.37.jar" \
          r8.jar $need_r8 "r8.jar (8.3.37 / d8 @ aliyun)" \
      || fail "r8.jar 下载失败。手动下载地址：https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.3.37/r8-8.3.37.jar 放到 tools/ 下即可"
  fi
fi

# ---- apksig.jar ----
if ! check_size apksig.jar $need_apksig; then
  rm -f apksig.jar
  fetch "https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.2.2/apksig-8.2.2.jar" \
        apksig.jar $need_apksig "apksig.jar (8.2.2)" \
    || fetch "https://maven.aliyun.com/repository/google/com/android/tools/build/apksig/8.2.2/apksig-8.2.2.jar" \
             apksig.jar $need_apksig "apksig.jar (8.2.2 @ aliyun)" \
    || fail "apksig.jar 下载失败"
fi

# ---- Shizuku ----
if [ ! -f shizuku/api/classes.jar ] || [ ! -f shizuku/provider/classes.jar ] \
   || [ ! -f shizuku/aidl/classes.jar ]; then
  rm -rf shizuku
  mkdir -p shizuku
  cd shizuku
  base="https://repo1.maven.org/maven2/dev/rikka/shizuku"
  if [ "$MODE" = "cn" ]; then
    base="https://maven.aliyun.com/repository/central/dev/rikka/shizuku"
  fi
  for a in api provider aidl; do
    fetch "$base/$a/13.1.5/$a-13.1.5.aar" "$a.aar" 2000 "shizuku $a.aar" \
      || fail "Shizuku $a 下载失败"
    mkdir -p "$a"
    unzip -o -q "$a.aar" classes.jar -d "$a" || fail "shizuku $a.aar 里没有 classes.jar"
    [ -f "$a/classes.jar" ] || fail "shizuku $a/classes.jar 解压失败"
    rm -f "$a.aar"
  done
  cd ..
fi

echo
echo "依赖就绪："
for f in android.jar r8.jar apksig.jar shizuku/api/classes.jar \
         shizuku/provider/classes.jar shizuku/aidl/classes.jar; do
  if [ -f "$f" ]; then
    printf "  %-32s %s\n" "$f" "$(wc -c < "$f" | tr -d ' ') 字节"
  fi
done
