# 图标资源

这里放的是**设计源与迭代记录**。构建实际使用的图标在 `src/res/mipmap-*/`，
打包时由 `build.sh` 直接从这里读 `src/res/`，与本目录无关。

## 目录

| 目录 | 内容 | 说明 |
|---|---|---|
| `adaptive/` | 自适应图标素材（432×432） | 当前方案：背景层 + 前景层 + 合成预览 |
| `drafts/` | 早期 v1~v4 迭代与对比图 | 仅作历史参考，不参与构建 |
| `ui/` | 界面内图标（24×24 线性） | 档位/启动/还原/设置等按钮图形 |
| `gen/` | 生成脚本与 API 请求 | 用 `minis-model-use` 批量生成 SVG |

## adaptive/ —— 当前应用图标

| 文件 | 用途 |
|---|---|
| `adaptive_bg.png` | 背景层（432×432，108dp @ xxxhdpi 基准） |
| `adaptive_fg.png` | 前景层，安全区按自适应图标规范留白 |
| `adaptive_icon_108.png` | 合成后的完整图标预览 |
| `bg108.png` / `fg108.png` | 独立裁切的层，给 `tools/mkadaptive.py` 用 |
| `big.png` (432) / `small.png` (96) | 应用商店与通知栏用的单图导出 |
| `adaptive_preview*.jpg` | 桌面上的实际效果对照 |

`src/res/mipmap-mdpi` ~ `xxxhdpi` 五档 PNG 由上面这些素材缩放而来，
时间戳与本目录基本同批（10-27 23:04~23:06）。

## ui/ —— 界面内图标

三套并存，因为生成过程是「先出 SVG 再渲染 PNG」：

- `c_*.svg` —— 第一版概念稿（8 个）
- `out_*.svg` —— 模型输出的最终 SVG（8 个，名称与 `gen/items.txt` 对应）
- `r_*.png` —— `out_*.svg` 渲染出的位图（24×24）
- `iconsheet.jpg` —— 全部图标的对照总览
- `sheet.html` —— 对照总览的源文件

**注意**：这些图标目前**没有接入构建**。`src/res/` 下只有 `mipmap-*`，
没有 `drawable` 目录，界面上的图标位置现在用的是文字/符号（如 `›`、`✓`）。
要用起来需要新建 `src/res/drawable/` 并改 `Ui.java` 的调用。

## gen/ —— 生成脚本

`gen.sh` 逐行读 `items.txt`（格式 `名称|中文描述`），拼成请求体调用
`minis-model-use run --model mimo-v2.6-pro`，把返回的 `<svg>` 存成 `out_<名称>.svg`。

```sh
cd icon/gen
sh gen.sh
```

脚本写死了输出到当前目录，所以必须在 `gen/` 里跑，否则 SVG 会散落到别处。

`mimo_req.json` / `req_batch1.json` 是单条请求与批量请求的样例。

## 待办

- [ ] `src/res/mipmap-*` 与 `adaptive/` 素材的对应关系未逐一核对，换图标时以
      `adaptive/adaptive_icon_108.png` 为准重新导出五档
- [ ] `ui/` 的 8 个界面图标尚未接入 `src/res/drawable/`
