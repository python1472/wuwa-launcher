# 项目状态摘要

> 用于快速恢复上下文。更新于 2026-10-28 01:05

## 一、项目位置

```
/var/minis/workspace/launcher/
├── README.md              项目说明
├── CONTEXT.md             项目状态摘要（接手先看这个）
├── build.sh               一键构建：依赖检查 → 清单 → 编译 → dex → 打包 → 签名
├── signing/keystore.p12   签名密钥（不公开，构建时自动生成）
├── src/com/miaojian/wuwalauncher/   17 个类
├── tools/                 构建工具链 + 6 个模拟测试 + 依赖/打包脚本
├── docs/design/           各阶段 UI 设计稿
├── docs/archive/          完整开发对话（44.5 万字）
├── icon/                  图标设计源（adaptive / drafts / ui / gen）
└── dist/                  构建产物：APK + 离线包（不进 git）
```

产物：`dist/鸣潮启动器.apk`（约 250KB），同步到 `/var/minis/attachments/wuwa_launcher.apk`

### 源码包的两种分发形态

| 形态 | 内容 | 体积 | 构建方式 |
|---|---|---|---|
| GitHub 仓库 | 不含 `tools/*.jar`（见 `.gitignore`） | 约 2.5MB | `fetch_deps.sh` → `build.sh` |
| 离线包 | 含全部构建依赖 | 约 37.5MB | 直接 `build.sh` |

`tools/pack_offline.sh` 生成离线包，自动排除 keystore / build / apk。

## 二、当前装机状态

- App 版本：`versionName=1.3`，设备上最后更新 2026-10-28 00:11
- 屏幕：原生 1280×2800 / 560dpi
- 权限已授予：Shizuku、悬浮窗、修改系统设置、实况通知
- 装机包与源码一致（用户确认「是同一版」），可直接在此基础上继续改

## 三、核心概念（用户定义，勿混淆）

**三个档位的真实含义**

| 档位 | 含义 | 需要什么 |
|---|---|---|
| 原生 | 完全不干预，游戏按自己默认跑 | 什么都不需要 |
| 高清 | **用悬浮窗矫正方向**，让游戏按屏幕物理分辨率渲染 | 悬浮窗权限（不需 Shizuku） |
| 极限/自定义 | 在矯正方向基础上再抬倍率（1.4~3.0×） | 悬浮窗 + Shizuku |

**方向矫正的原理**

游戏内部声明横屏，系统会给它一个交换宽高的窗口，渲染尺寸上不去。
挂一个 1×1 透明悬浮窗、把 `screenOrientation` 设为 `SENSOR`，
系统按屏幕真实宽高给窗口，游戏才会按屏幕分辨率渲染。

注意：**不是** `fixed-to-user-rotation`（那个会锁死角度，导致画面缩在顶部）。

## 四、关键决策与踩过的坑

**⚠ 设计决策：开放最大自定义，不做像素上限（2026-10-28，用户明确拍板）**

曾经试过加一层「按内存动态算像素红线 + 超限需实验档解锁 + 确认按钮冷却 5 秒」的护栏，
**用户全部否决，已完整回滚**。原话：「警告已经警告过了，开放最大程度的自定义给用户」。

因此以下三件事**不要做**，做了会被打回：

- ❌ 按内存/像素算上限，锁住自定义倍率
- ❌ 「实验档」之类的二级开关（3.0× 开箱即用，不需要先解锁）
- ❌ 在免责声明上加冷却时间/按住确认等额外操作成本

保留的现状：

- 自定义倍率 1.0× ~ 3.0×（0.1 一档，共 21 档）**全部可选**
- 唯一的拦截是免责声明：`scale > 1.6f` 时弹一次（`CONFIRM_THRESHOLD`）
- 用户自己承担「崩溃后屏幕停在超高分辨率」的后果

判断边界：**加固可以，加限制不行。** 崩溃提示、日志、文档说明都欢迎；改变用户能选什么，先问。

**构建链**
- 大件依赖不进 git（`.gitignore` 里 `tools/*.jar`、`tools/shizuku/`），靠 `tools/fetch_deps.sh` 下载
- `r8.jar` 旧地址 `storage.googleapis.com/r8-releases/raw/main/r8.jar` **已 404**，
  改走 Google Maven 钉死版本：`com/android/tools/r8/8.3.37/r8-8.3.37.jar`
- 旧脚本的 `curl` 没带 `-f`，404 会把 189 字节 XML 错误页存成 `r8.jar`，
  失败点在 4 步之后的 d8（`Could not find or load main class`），极难排查。
  现在 `curl -f` + 下载后校验字节下限 + `build.sh` 第 0 步再验一次能不能 `unzip -l`
- `signing/keystore.p12` 外发会让别人编出同签名包，也可能被滥用；离线包/仓库都不含它，
  代价是自编译包与官方版签名不同、不能覆盖安装（已在 README 写明）

**测试与产物基线（2026-10-28 00:45）**

- `sh tools/selftest.sh` 六模块：32 / 14 / 16 / 27 / 20，共 109 项全过
- `鸣潮启动器.apk` = 246,830 字节（249.8KB），md5 `11033184d959ee1d98d663a2e6277c8a`
- 离线包在干净目录解包后**不跑 fetch_deps 直接 build 成功**，产物字节数一致

**悬浮窗**
- 必须去掉 `setAlpha(0f)`：alpha=0 的窗口系统判为不可见，方向请求被忽略
- 用 `SCREEN_ORIENTATION_SENSOR` + `accelerometer_rotation=1` 配套

**还原逻辑**
- 判断依据是 `wm size` 输出里的 `Override size:` 行（系统真实状态，不会因清数据丢失）
- 试过 `wm size` 设成和物理分辨率相同的值：**系统不认为这是改动**，不产生 Override
- 快照只在「无 Override 的干净状态」下记录，已有改动时沿用旧记录
- 改动标记用累加语义（`addChanged`），不能覆盖

**通知**
- 前台通知只能由服务自己撤，外部 `cancel()` 无效，必须 `stopForeground(STOP_FOREGROUND_REMOVE)`
- 通 Knowledge 渠道 ID 一旦创建，重要性就固定，改代码无效，得换新 ID
- 我们**不**在 `onDestroy` 里还原（服务回收 ≠ 用户想退出）

**流体云（结论：此路不通）**
- 走的是 Android 16 标准接口 `POST_PROMOTED_NOTIFICATIONS` + `setRequestPromotedOngoing` + `ProgressStyle`
- 用户机器 `16.0.0.212` 上全部条件满足（`hasPromotableCharacteristics=true`），SystemUI 仍不渲染胶囊
- 根因：ColorOS 只认自家白名单 `fluid_seeding_notification` 里的 `serviceId`，非标准 API 能解决的
- 该功能做成设置里的「测试」项，**默认关闭**

**UI**
- Activity 必须 `setTheme(Theme_DeviceDefault_NoActionBar)`，否则分辨率变化重绘时露出标题栏
- `NumberPicker` 上下相邻项颜色跟随主题（黑），深色界面上看不见 → 自己写 `WheelPicker`
- 触摸轨迹按**时间**淘汰（900ms），不按点数截断，否则滑到一半断掉
- 二次确认阈值 1.6×：≤1.6 直接测试，>1.6 弹免责声明

## 五、测试

```sh
sh tools/selftest.sh              # 六个模块，109 项
sh tools/fetch_deps.sh --check    # 只校验构建依赖是否完整
```

| 模块 | 内容 | 数量 |
|---|---|---|
| sim_verify | 源码命令与模拟器一致性 | — |
| sim_test | 主流程 | 32 |
| sim_edge | 边界场景 | 14 |
| sim_restore | 还原判断 | 16 |
| sim_ui | UI 状态机 | 27 |
| sim_lifecycle | 生命周期 | 20 |

## 六、待办

- [ ] 图标：等 Mimo 出稿后替换（`icon/gen/mimo_req.json` 已备好请求）
- [ ] 界面内 8 个线性图标（`icon/ui/`）尚未接入 `src/res/drawable/`
- [ ] 按 Home 三选项弹窗（设计稿在 `docs/design/home_dialog3.html`，未实现）
- [ ] 主色自定义
- [x] ~~3.0× 实验档解锁开关~~ → **已否决**，见第四节设计决策。3.0× 保持开箱可用

## 七、设备环境

- realme RMX6688 / 天玑 9400+ / Mali Immortalist-G925 MC12
- ColorOS 16.0.0.212（用户自行降级，原为 500）
- 屏幕 1280×2800，横屏 2800×1280
- 内存 11.4G，可用约 900MB（CPU/GPU 共用）
- **当前无 root**（`/system/bin/su` 不存在），只能靠 Shizuku

## 八、交接须知

新接手时按这个顺序读，别急着动手：

1. **本文第四节开头的设计决策** —— 最容易踩雷。想做「保护用户」的加固之前先看一遍
2. `README.md` 第二节（三个原理）—— 分辨率/方向/还原为什么这么做
3. `docs/archive/README.md` —— 44.5 万字的完整开发对话，重大结论的原始依据

三条硬规矩：

- **没让你改就别改。** 上下文交接 ≠ 授权重构。想动哪块先问，得到「改」字再碰代码
- **先看 `tools/selftest.sh` 跑不跑得过**，再决定改什么。基线是 109 项全过
- **改完要重跑自测 + 重建 APK**，并把字节数/md5 记回本文第四节

本次（2026-10-28）交接时发生过一次越界：接手后自作主张实现了「动态像素上限 + 实验档」，
被要求全部回滚。教训写在第四节，别重演。

