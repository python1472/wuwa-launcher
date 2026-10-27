#!/bin/sh
i=0
while [ $i -lt 20 ]; do
  line=$(sed -n "$((i+1))p" items.txt)
  [ -z "$line" ] && break
  name=$(echo "$line" | cut -d'|' -f1)
  desc=$(echo "$line" | cut -d'|' -f2)

  printf '{"messages":[{"role":"system","content":"只输出一段 SVG 代码，不要解释，不要 markdown 标记。"},{"role":"user","content":"画一个 24x24 线性图标：%s\\n\\n要求：viewBox=\\"0 0 24 24\\"，stroke=\\"currentColor\\" stroke-width=\\"1.8\\" fill=\\"none\\" stroke-linecap=\\"round\\" stroke-linejoin=\\"round\\"，元素不超过 4 个。直接输出 <svg>...</svg>。"}]}' "$desc" > /tmp/req.json

  timeout 200 minis-model-use run --model mimo-v2.6-pro --input /tmp/req.json 2>/dev/null \
    > /tmp/resp.json

  python3 -c "
import json,re,sys
try:
    d=json.load(open('/tmp/resp.json'))
    t=d.get('text','')
    m=re.search(r'<svg.*?</svg>', t, re.S)
    if m:
        open('out_$name.svg','w').write(m.group(0))
        print('$name OK %d 字节' % len(m.group(0)))
    else:
        print('$name 无SVG')
except Exception as e:
    print('$name 失败', e)
"
  i=$((i+1))
done
