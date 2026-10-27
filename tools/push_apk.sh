#!/bin/sh
# 把沙箱里的 APK 分块传到设备 /sdcard/Download
#
# 关键点：循环体里的 android-shizuku-cli 必须重定向 stdin。
# 它会把 while read 的输入吃掉，导致跳块、文件损坏（第一次就栽在这）。
# 另外同时写出并比对校验和，不匹配就报错，不静默放过。

SRC="$1"
DST="$2"
TMP=/sdcard/Download/.wuwa_chunks.b64

if [ ! -f "$SRC" ]; then
  echo "源文件不存在：$SRC"
  exit 1
fi

SRC_SIZE=$(wc -c < "$SRC")
SRC_MD5=$(md5sum "$SRC" | cut -d' ' -f1)
echo "源文件：$SRC_SIZE 字节  md5=$SRC_MD5"

android-shizuku-cli exec "rm -f $TMP" >/dev/null 2>&1

base64 -w0 "$SRC" | fold -w 700 > /tmp/_chunks.txt
echo >> /tmp/_chunks.txt   # 只保证末行有换行，否则 while read 会漏掉最后一块
# fold 生成的最后一行没有换行符，补一个空行保证 while read 读得到。
COUNT=$(wc -l < /tmp/_chunks.txt)
echo "分块数：$COUNT"

i=0
while read -r chunk; do
  i=$((i + 1))
  [ -z "$chunk" ] && continue
  # < /dev/null 是必须的，否则会吃掉循环的输入
  android-shizuku-cli exec "printf '%s' '$chunk' >> $TMP" < /dev/null >/dev/null 2>&1
done < /tmp/_chunks.txt

echo "已发送 $i 块，正在解码…"
android-shizuku-cli exec "base64 -d $TMP > $DST && rm -f $TMP" < /dev/null >/dev/null 2>&1

RESULT=$(android-shizuku-cli exec "md5sum $DST; wc -c $DST" < /dev/null 2>/dev/null \
  | grep -oE '[0-9a-f]{32}|[0-9]+ /sdcard[^ ]*' | head -2)

echo "设备端校验：$RESULT"

if echo "$RESULT" | grep -q "$SRC_MD5"; then
  echo "校验通过"
else
  echo "校验失败：文件损坏，请重传"
  exit 1
fi
