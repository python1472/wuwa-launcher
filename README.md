# 鸣潮启动器（Wuwa Launcher）

> **本项目全程由 DeepSeek V4.1 Flash 制作。**

给《鸣潮》Android 端调渲染分辨率与屏幕方向的辅助工具。**免 Root**，通过 Shizuku 获取 shell 身份。

包名 `com.miaojian.wuwalauncher` · 最低 Android 7.0（API 24）· 纯 Java，无第三方 UI 框架

---

## 一、这个 App 做什么

| 能力 | 依赖 | 说明 |
|---|---|---|
| 提升渲染分辨率 | Shizuku | `wm size` + `wm density`，按屏幕物理分辨率自动计算档位 |
| 切换三键导航 | Shizuku | 高分辨率下全面屏手势区域会失效，自动切三键 |
| 控制屏幕方向 | 悬浮窗权限 | 1×1 透明窗口，压住游戏自己声明的方向 |
| 退出自动还原 | 前台服务 | 划掉任务即还原，进程被杀则下次打开补还原 |

**不需要**：Root、无障碍服务、Xposed。

---

## 二、三个关键原理

### 1. 分辨率：`wm` 命令只能由 shell 执行

普通 App 只能改 `Settings.System` 里的值（如 `accelerometer_rotation`），但改不了分辨率——那属于窗口管理器，必须 shell 身份。所以走 Shizuku。

改尺寸时**密度必须按同一比例一起改**，否则界面元素会忽大忽小：

```
渲染宽 = 物理宽 × 倍数
渲染高 = 物理高 × 倍数
密度   = 物理密度 × 倍数
```

倍数按像素总量限制。本机实测 1280×2800（3.58M 像素）时：

| 档位 | 渲染尺寸 | 密度 | 像素量 | 判断 |
|---|---|---|---|---|
| 1.4× | 1792×3920 | 784 | 7.0M | 稳妥 |
| 1.6× | 2048×4480 | 896 | 9.2M | 有风险 |
| 1.8× | 2304×5040 | 1008 | 11.6M | 风险高 |
| 2.0× | 2560×5600 | 1120 | 14.3M | 极易崩溃 |

> 2K 屏手机同一个 1.4× 就会到 9M 像素，所以档位必须**按屏幕现算**，不能写死。

### 2. 方向：1×1 透明悬浮窗

系统按「最上层窗口请求的方向」决定显示方向。挂一个全透明、不可触摸的窗口盖在游戏上，把方向写进窗口参数，游戏自己声明的横屏就被压住了。

```java
params = new WindowManager.LayoutParams(1, 1,
        TYPE_APPLICATION_OVERLAY,
        FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR; // 跟随重力
wm.addView(overlay, params);
```

- `SCREEN_ORIENTATION_SENSOR` → 跟随重力（交给系统判断，不自己算角度）
- `SCREEN_ORIENTATION_PORTRAIT` → 强制竖屏

**不要用 `wm fixed-to-user-rotation enabled`**：那是显示级粘性设置，会把游戏画面锁死在错误比例（实测会缩在屏幕顶部且退出后仍残留）。

## 三、还原路径

```java
点「还原显示」            → 立即还原
从最近任务划掉启动器      → GuardService.onTaskRemoved
按 Home（尚未实现）       → 三选项弹窗：还原并回桌面 / 保持并回游戏 / 保持并回桌面
下次打开 App              → 检测到未还原时弹窗询问，用户可选择还原或保持现状
```

还原的是**进入前的快照**（`Prefs` 里存的导航模式、旋转设置），不是硬编码的默认值。

**刻意不在 `GuardService.onDestroy` 里还原**：服务可能因系统回收或前台化失败被销毁，
那一刻游戏往往还在跑，还原会把游戏的分辨率改回去。服务销毁 ≠ 用户想退出。

**补还原前必须确认游戏已退出**：先 `pidof` 查鸣潮，在运行就说明是正常会话，不动。

---

## 四、代码结构

```
launcher/
├── build.sh                     一键构建（产物输出到 dist/）
├── README.md                    本文件
├── CONTEXT.md                   项目状态摘要（接手前先看这个）
├── signing/keystore.p12         签名密钥（构建时自动生成，不外发）
│
├── src/
│   ├── com/miaojian/wuwalauncher/    17 个 Java 类
│   └── res/mipmap-*/                 启动图标的 5 档 PNG + 自适应图标 XML
│
├── tools/
│   ├── fetch_deps.sh            下载构建依赖（带校验/镜像/--check）
│   ├── pack_offline.sh          打离线构建包（含依赖，约 38MB）
│   ├── selftest.sh              跑全部模拟测试（6 模块 109 项）
│   ├── push_apk.sh              分块推送 APK 到设备并经 md5 校验
│   ├── mkmanifest.py            生成二进制 AndroidManifest.xml（无 aapt2）
│   ├── mkresources.py           生成 resources.arsc
│   ├── mkadaptive.py            生成自适应图标 XML
│   ├── mkzip.py                 重打包做 4 字节对齐
│   ├── dump_axml.py             清单自检（解析二进制 XML）
│   ├── Sign.java                基于 apksig 的签名工具
│   ├── sim_*.py                 6 个模拟测试 + 设备模型
│   ├── android.jar              Android 34 SDK 桩（不进 git）
│   ├── r8.jar                   d8（class → dex，不进 git）
│   ├── apksig.jar               APK 签名库（不进 git）
│   └── shizuku/                 Shizuku API / Provider / AIDL（不进 git）
│
├── docs/
│   ├── design/                  各阶段 UI 与流程设计稿（HTML）
│   │   └── screens/             渲染出的效果图
│   └── archive/                 完整开发对话（44.5 万字）
│
├── icon/                        图标设计源与迭代记录
│   ├── adaptive/                当前应用图标素材（432×432）
│   ├── drafts/                  v1~v4 早期迭代，仅参考
│   ├── ui/                      界面内 24×24 线性图标（尚未接入构建）
│   └── gen/                     批量生成 SVG 的脚本与请求
│
└── dist/                        构建产物（不进 git）
    ├── 鸣潮启动器.apk
    └── wuwa-launcher-offline-<日期>.tar.gz
```

### src 下的 17 个类

| 文件 | 职责 |
|---|---|
| `LauncherActivity.java` | 主界面 + 流程编排（1186 行） |
| `Ui.java` | 视图构建工具（尺寸换算 / 圆角 / 文字样式 / 配色） |
| `TierTester.java` | 渲染档测试（10 秒倒计时 + 轨迹 + 二次确认） |
| `DisplayOps.java` | 分辨率 / 密度 / 导航 / 方向 的读写与档位计算 |
| `RotationOverlay.java` | 方向控制悬浮窗（含 3 次重试） |
| `Shell.java` | Shizuku shell 执行（全项目唯一出口） |
| `Restorer.java` | 还原逻辑（Activity 与服务共用） |
| `Prefs.java` | 改动前状态快照 |
| `GuardService.java` | 前台守护服务，负责退出还原 |
| `BinderProvider.java` | Shizuku binder 接收端 |
| `RestoreNotification.java` | 还原通知与流体云胶囊（测试项） |
| `RestoreActionReceiver.java` | 通知按钮的广播接收 |
| `SettingsPanel.java` | 设置面板（实验项 / 重启桌面） |
| `CustomTierDialog.java` | 自定义倍率滚轮与免责声明 |
| `WheelPicker.java` | 自绘滚轮（替代 NumberPicker） |
| `TouchTrailView.java` | 测试时的触摸轨迹 |
| `Permissions.java` | 运行期权限引导 |

### 模块职责划分

| 文件 | 只做一件事 |
|---|---|
| `Shell` | 执行 shell 命令并回传输出。其它模块不允许直接调 Shizuku |
| `DisplayOps` | 屏幕参数读写、档位计算、分辨率/密度/导航的增删改 |
| `RotationOverlay` | 悬浮窗的挂载与移除，失败重试 |
| `TierTester` | 档位测试的倒计时、轨迹、二次确认，结束即还原 |
| `Restorer` | 还原调用的唯一入口 |
| `Prefs` | 原始状态的存取 |
| `Ui` | 配色、尺寸换算、圆角背景、文字样式 |
| `LauncherActivity` | 只负责界面组装与流程编排，不做具体操作 |

---

## 五、界面与流程

### 主界面

```
鸣潮启动器                              ●（绿=已授权/黄=未授权）
┌────────────────────────────────┐
│ Shizuku 已授权 · 分辨率可用      │
└────────────────────────────────┘

屏幕分辨率
┌────────────────────────────────┐
│ 原生        1080p · 游戏默认     │
│ 高清        屏幕分辨率 · 2800×1280│
│ 最大渲染极限  按屏幕自动计算      │
└────────────────────────────────┘
   ┌─ 展开后 ────────────────────┐
   │ 1.4×  1792×3920 · 784dpi  稳妥│
   │ 1.6×  2048×4480 · 896dpi  有风险│
   │ 1.8×  2304×5040 · 1008dpi 风险高│
   │ 2.0×  2560×5600 · 1120dpi 极易崩溃│
   └────────────────────────────┘

┌────────────────────────────────┐
│           启动游戏               │  ← 未选分辨率时灰色不可点
└────────────────────────────────┘

┌────────────────────────────────┐
│ 日志区                          │
└────────────────────────────────┘

┌────────────────────────────────┐
│           还原显示               │
└────────────────────────────────┘
```

### 主流程

```
① 打开 App
   └─ 不修改任何设置，只申请 Shizuku 授权
      ├─ 已授权  → 分辨率 / 密度 / 导航可用
      └─ 未授权  → 跳过这些，方向控制仍可用

② 选择分辨率
   ├─ 原生        → 不改尺寸，只锁方向
   ├─ 高清        → 用屏幕物理分辨率
   └─ 最大渲染极限 → 展开四档 → 进入测试
      └─ 点任意档位 → 应用该分辨率 → 10 秒倒计时
         ├─ 滑动屏幕看轨迹是否跟手
         ├─ 点「确认无误」→ 二次确认 → 确认则提前结束
         └─ 超时 → 自动结束
            └─ 结束都先还原屏幕 → 标记「可用」并选定

③ 点「启动游戏」
   ├─ 挂方向悬浮窗（失败重试 3 次）
   │  └─ 3 次全失败 → 弹窗「重试/取消」，游戏不启动，屏幕未改动
   ├─ 改分辨率 + 密度（原生档跳过）
   ├─ 切三键导航（仅高分辨率档）
   └─ 拉起鸣潮

④ 退出
   ├─ 点「还原显示」
   ├─ 从最近任务划掉 → 服务触发
   └─ 进程被杀 → 下次打开补还原
```

### 失败分支处理约定

| 情况 | 行为 |
|---|---|
| 悬浮窗挂不上 | 重试 3 次 → 弹窗，**不启动游戏**，屏幕不动 |
| Shizuku 未授权 | 跳过分辨率/导航，仍可方向控制，日志注明 |
| 找不到游戏 | 日志提示，按钮恢复可用 |

---

## 六、构建

```sh
sh tools/fetch_deps.sh    # 首次：下载 42MB 构建依赖（不进 git）
sh build.sh               # 构建
```

产物：`dist/鸣潮启动器.apk`（约 250KB）

依赖：`openjdk17`（提供 `javac` / `keytool`）。无需 Android SDK / Gradle / aapt2——
清单由 `tools/mkmanifest.py` 直接生成二进制 AXML。

### 构建依赖

大体积依赖不进 git（见 `.gitignore`），首次构建前需要下载：

| 文件 | 大小 | 用途 |
|---|---|---|
| `tools/android.jar` | 25 MB | Android 34 SDK 桩，`javac` 编译时做类型检查 |
| `tools/r8.jar` | 16 MB | 内含 d8，`.class` → `classes.dex` |
| `tools/apksig.jar` | 476 KB | APK 签名 |
| `tools/shizuku/*/classes.jar` | 64 KB | Shizuku API / Provider / AIDL |

只在下一次编译时用，**不会进入 APK**。最终 APK 约 250KB，自包含。

```sh
sh tools/fetch_deps.sh --check      # 只校验现有依赖是否完整
sh tools/fetch_deps.sh --mirror CN  # 国内镜像优先
```

`fetch_deps.sh` 带 `-f` 与下载后的大小校验：源站 404 或返回错误页时会当场失败，
不会把错误页存成 jar 留到后面 d8 才炸。`build.sh` 开头也会再验一次，
依赖缺失直接退出并提示该跑哪个命令。

### 离线构建包

不想联网、或网速不稳时，打一个带依赖的完整包：

```sh
sh tools/pack_offline.sh          # → dist/wuwa-launcher-offline-<日期>.tar.gz（约 38MB）
sh tools/pack_offline.sh 1.3      # 指定版本标签
```

解包后直接 `sh build.sh`，无需 `fetch_deps.sh`。包内含 `OFFLINE.md` 说明。

包内**不含** `signing/keystore.p12`。

### 关于签名

`signing/keystore.p12` 不进仓库也不进离线包。`build.sh` 发现它不存在时会
自动生成一份新密钥，因此：

- **自行编译的 APK 签名与官方发布版不同**，不能覆盖安装，会报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
- 想装自编译版本，需要先卸载官方版（会清掉 App 内设置）
- 自编译版本之间若 keystore 不同，也不能互相升级

签名私钥有意不外发。

### 首次安装后

```sh
pm install -r dist/鸣潮启动器.apk
```

App 需要的运行期条件：

1. **Shizuku 已启动并授权**（改分辨率必需）
2. **悬浮窗权限**（方向控制必需）：设置 → 应用 → 特殊权限 → 显示在其他应用上层

App 内会引导申请，无需手动执行 adb 命令。

---

## 七、已知问题与注意事项

0. **首次使用必须手动授予两个运行期权限**
   - 「显示在其他应用上层」→ 方向控制必需，缺了会直接弹窗引导
   - 「修改系统设置」→ 锁旋转用，缺了不致命，但退出后方向可能不还原

   这两个都不在安装时自动授予，App 内会给出跳转入口。

1. **改分辨率后启动动画异常**
   系统会把新启动的窗口先按旧边界算动画、再跳到全屏，表现为「圆角小窗 → 突然铺满」。
   这是系统过渡动画与分辨率变更的时序冲突，App 侧只能靠延后启动来规避，无法根除。
   实测：删掉 `setting_app_startup_anim_speed` 回默认值后，需**重启桌面进程**才生效。

2. **`setting_app_startup_anim_speed = 0` 会让问题更明显**
   realme 桌面启动动画速度被设为 0 时，圆角按缩放倍率放大，看起来更突兀。

3. **3 倍以上不要开放**
   本机 3× 是 3840×8400、32.3M 像素，单帧管线占用超过 1GB，必崩。
   崩溃时若还原命令未执行，屏幕会停在超高分辨率且**重启不清除**。

4. **游戏日志是加密的**
   `Client.log` 是密文，grep 不到 GPU 与 profile 信息。判断档位是否生效只能靠实测。

5. **`Engine.ini` 的 `[SystemSettings]` 会被游戏删除**
   游戏启动后会清掉该段，画质参数写在那里留不住。

6. **`shell` 执行必须并发读 stderr**
   顺序读取 stdout 再读 stderr，在输出超过管道缓冲区（约 64KB）时会死锁。
   `Shell.run()` 已用独立线程读 stderr 规避。

7. **只改分辨率还不够，必须同时打开自动旋转**
   悬浮窗请求的是 `SCREEN_ORIENTATION_SENSOR`（跟随重力），
   但若 `accelerometer_rotation=0`，方向会被 `user_rotation` 钉死，SENSOR 请求无效。
   两者必须配套。

---

## 八、待实现

- [ ] 按 Home 后的三选项弹窗（还原并回桌面 / 保持并回游戏 / 保持并回桌面，10 秒倒计时默认第一项）
- [ ] 游戏退到后台的检测（需 Shizuku 侧独立进程读 `/proc/<pid>/oom_score_adj`）
- [ ] 3.0× 实验档（需可靠的看门狗进程兜底）
- [ ] 应用图标
- [ ] 自定义主色

---

## 九、设计稿

`docs/design/` 下的 HTML 可直接用浏览器打开：

| 文件 | 内容 |
|---|---|
| `ui_v5.html` | 主界面三态（未选 / 已选 / 测试中） |
| `home_dialog3.html` | 按 Home 后的三选项弹窗 |
| `logic_tree.html` | 完整逻辑树 |
| `flow_v2.html` | 流程图 |

---

## 十、许可

[MIT](LICENSE)

可以自由使用、修改、分发、商用，保留版权声明即可。

### 免责

本工具会修改系统显示参数（`wm size` / `wm density` / 导航模式）。
超高倍率下若游戏因内存不足被系统杀死，还原命令可能来不及执行，
屏幕会停留在超高分辨率，且重启不清除。此时只能通过外部执行
`wm size reset` 与 `wm density reset` 恢复。

使用前请自行评估风险。
