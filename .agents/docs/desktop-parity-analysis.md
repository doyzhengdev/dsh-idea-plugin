# DSH for Desktop 与官方桌面端功能对齐分析

> 分析对象：`E:\github\deepseek-harness\apps\desktop-host`（下称 **desktop-host**）与
> `E:\github\deepseek-harness\apps\desktop`（下称 **desktop**），版本 `0.2.2-alpha.3`。
> 对照对象：本仓库的「原始桥」= `DshServerService`（起 dsh 子进程）+ JCEF 嵌 Web + Kotlin 注入 +
> `src/main/resources/dsh-plugin-jetbrains`（`index.js` Host 半边 / `client.js` Client 半边）。
>
> 目的：明确官方客户端的全部能力，并给出「原始桥要实现哪些功能才能与之对齐」的清单。
> 相关：`architecture.md`（本仓库架构）、`p0-quit-guard-and-recovery-plan.md`（P0 落地改造）。

## 0. 一句话结论

- **desktop-host** 是被 Electron 以 Node 模式 fork 出来的**私有 Host 进程**：负责把共享 Web 应用
  （`desktop` profile）跑起来，并经 Node IPC 向壳汇报 URL/启动注入/账号凭证/退出检查。
  职责 ≈ 本仓库 `dsh-plugin-jetbrains/src/index.js` + `DshServerService` 进程托管部分的放大版。
- **desktop** 是 **Electron 壳**：自定义协议承载 Web、窗口/托盘/退出、preload 页面桥、原生文件与拖拽、
  账号登录与内嵌 Platform 页、更新器、命令安装、运行时打包、崩溃诊断。
  职责 ≈ 本仓库 Kotlin 插件 + JCEF + `client.js`。
- 因此「对齐」= 在 JCEF 环境里重建官方 `desktop` 通过 `preload` 暴露给 Web 前端的**页面桥**
  （`dshDesktop` 等），以及 `desktop-host` 的 **Host 侧能力**（退出检查、file-drag 授权、账号凭证、诊断）。
  决定「用起来像不像官方客户端」的核心是 §4 的**页面桥契约表**。

**架构差异要点**：官方**自己 serve 静态前端 + 稳定 `dsh-app://` origin + boot IPC 注入 + 反代 API/WS**
（`apps/desktop/src/web-document.ts:18,43,65,97`、`apps/web/src/main.ts:17-36`）；
本仓库是**固定端口直接加载 Host URL**（`DshPanel.kt:188`、`DshServerService.kt:266-274`）。
后果：官方 localStorage 天然稳定；本仓库靠「固定端口 + 快照兜底」（`client.js` 的 `StorageSnapshot`）。
官方能先画加载页再注入；本仓库只能等 Host 起来。

## 1. desktop-host 拆解

**定位**：包名 `@deepseek-ai/dsh-desktop-host`，`private`，被 Electron 以
`ELECTRON_RUN_AS_NODE=1 --expose-internals` 拉起（`host-process.ts:212-224`）。

**启动契约**（`src/index.ts:22-48`）：
- argv：`runtimeDir`、`projectDir`、`primaryRuntime`、`pnpm 入口`、`nodeBin`。
- `runProfile({ profile: 'desktop', args: ['--no-open','--host','127.0.0.1','--port','0'] })`
  —— 桌面端**不是**走 CLI `--profile desktop`（CLI 硬拒），而是直接 `runProfile`。
- 就绪上报 `ready` + `url` + `injections`（`index.ts:142-143`，`webServer.collectIndexInjections`）。

**IPC 事件集合**（`index.ts:72-128`、`host-process.ts:28-47`）：
`starting / ready / fatal / shutdown-complete / platform-session / plugin-operation /
file-drag-resource / update-tasks / quit-inspection`。

| 能力 | 实现 | 作用 |
|---|---|---|
| Host 进程托管 | `index.ts` | 起 profile、上报 URL/注入、处理 `shutdown` |
| Office 引擎解析 | `office-engine.ts:23-47` | ASAR 内 `libreoffice-kit` 原生路径重写（纯 Node 安装无此需求） |
| Office 组合 | `office.ts:28-38` | 装载 `dsh-tool-workspace-dependencies` + `dsh-skill-office`（Python/Node 创作资源） |
| 文件拖拽授权 | `file-drag.ts:25-63` | 经组合 `fs`+`sandboxPolicy` 实际读取、算 sha256、返回本地路径 |
| 平台凭证 | `platform-session.ts:10-35` | 账号凭证只走私有 IPC，绝不进渲染进程 |
| 操作诊断 | `operation-diagnostics.ts:12-33` | 插件管理操作 → 壳做持久化 |
| 退出检查 | `quit-inspection.ts:16-39` | `activeTasks`（跑着的 agent/子代理/排队/后台任务）+ `scheduledTasks` |
| 更新准入锁 | `update-tasks.ts:16-61` | `inspect/lock/unlock`：拒新请求、排空、复查任务 |
| 致命诊断 | `fatal-diagnostic.ts:21-58` | 64KiB 上限的启动错误 + 启动元数据 |
| CLI 模式 | `cli.ts` + `windows-cli-signals.ts` | 自带 pnpm 跑普通 `dsh` CLI、注册 Windows 控制台信号 |

## 2. desktop 拆解（Electron 壳）

| 域 | 关键文件 | 职责 |
|---|---|---|
| 协议 / Web 承载 | `web-document.ts`、`main.ts:787-803` | `dsh-app://` 自定义协议；本地 serve 前端；反代 API；换 cookie、去连接级头、插件 bundle `no-store`、清理遗留 `dsh-auth-*` |
| 窗口 / 生命周期 | `main.ts:262-368`、`single-instance.ts`、`tray.ts`、`background-notice.ts`、`quit-confirmation.ts` | 主窗口/欢迎窗口、关闭=隐藏、托盘、单实例、退出检查+确认 |
| 页面桥(preload) | `preload-app.ts`、`ipc.ts`、`platform-ipc.ts` | 暴露 `dshDesktop` / `__DSH_DIRECTORY_PICKER__` / `__DSH_HOST_PATHS__` / `__DSH_LOCALE__` / `dshOnboarding` / `dshPlatform` / `dshDesktopBoot` |
| 原生能力 | `file-actions.ts`、`file-drag.ts`、`directory-picker.ts`、`microphone-permissions.ts`、`browser-guests.ts`、`platform-view.ts` | 保存/剪贴板图、原生拖出、目录选择、麦克风、webview 租约隔离、内嵌用量/充值页 |
| 账号 / 引导 | `account-backend.ts`、`welcome-backend.ts`、`welcome-window.ts`、`policy-test-auth.ts` | PKCE 登录、状态流、欢迎窗口、测试鉴权 |
| 更新 | `update-coordinator/schedule/channel/settings/dialog/overlay/window.ts`、`mandatory-update-policy/window.ts`、`src/updates/electron-updater/*` | 检查/下载/校验/安装交接、渠道、强更策略 |
| 命令 | `command-installation.ts`、`command-management.ts` | `dsh` 命令注册/修复/移除 |
| 运行时 / 打包 | `runtime-tree.ts`、`core-package-set.ts`、`project-manager.ts`、`scripts/prepare-primary-runtime.ts` | 签名运行时描述符、核心包集、profile 初始化/恢复、Python+Node+pnpm+office-skills 载荷 |
| 诊断 / 恢复 | `crash-report.ts`、`fatal-recovery.ts`、`operation-journal/report/job/worker.ts` | 崩溃报告、原生恢复（退出/重启/禁用第三方插件+备份 patch）、近期操作记录 |
| 设置 / 语言 / 环境 | `settings-file.ts`、`locale.ts`、`locale-cache.ts`、`login-shell-environment.ts`、`node-environment.ts` | 壳本地设置、8 语言、语言缓存、macOS 登录 shell 环境 |

## 3. 本仓库「原始桥」现状（基线）

- **进程**：应用级单例 `DshServerService`，复用官方桌面客户端携带的运行时（Electron run-as-node +
  内嵌 CLI）；**单进程多项目**，`--profile idea --patch <overlay> --host 127.0.0.1 --port <固定/探测> --no-open`。
- **profile**：`idea` 链接到桌面端的 `desktop`，两者共用同一份配置（`DshProfileBootstrap.kt` / `ProfileManager.kt`）。
- **页面**：JCEF 直接加载 Host URL + `#ideWorkspace=`（`DshPanel.kt:188`）。
- **Cordis 插件** `dsh-plugin-jetbrains`：
  - `client.js`：ideWorkspace 登记/切换、侧栏只显当前项目、覆盖 `sidebarRight.openResource/openResourceIn`
    在 IDE 开文件、`__dshIdeInsertRefs` 往输入框插 `@` 引用、设置配置文件点击改写、localStorage 跨端口快照。
  - `index.js`：`GET/POST /dsh-ide/storage/load|save` 存 `<profile.dir>/web-storage/<profile>.json`；
    首次启动经官方 `configEditor` 写入语言/主题。
- **Kotlin 注入**（`DshPanel.kt:225-237`）：`__dshIdeOpenFile` / `__dshIdeRestartWeb` / `__dshIdePush` /
  `__dshIdeSettingsDocument` / `__dshIdeHost`。
- **已有等价项**：Host 进程启动+URL 上报、健康检查（含 303 处理）、杀进程树、Windows IDE 退出守护、
  共享 profile、语言主题继承、官方客户端运行时、设置弹窗。

## 4. 页面桥契约表（核心）

官方 Web 前端大部分桌面增强是「探测全局桥，缺失就降级」。JCEF 里这些全局不存在，于是 Web 退回普通浏览器能力。
要对齐就补这些（源码以 `packages/client/...` 为官方相对路径）：

| 前端期望的全局 | 启用什么 | 缺失时降级 | 官方出处 | 优先级 / 可行性 |
|---|---|---|---|---|
| `dshDesktop.fileActions` | 保存原文件到磁盘、远程图复制到剪贴板 | 浏览器下载 / 无远程图剪贴板 | `ui-file-actions/src/client/index.ts:25`；`desktop/src/preload-app.ts:19-24`；`desktop/src/file-actions.ts` | **P1**；JCEF→Kotlin 暴露保存对话框 + 下载 + 远程图抓取 |
| `dshDesktop.fileDrag` | 把会话文件/图片**原生拖出**到资源管理器/Finder | 无 | `ui-file-drag/src/client/index.ts:32-33`；`desktop-host/src/file-drag.ts` | **P2**；JCEF 起拖受限，先做「复制到临时文件+提示路径」 |
| `__DSH_HOST_PATHS__.pathFor(file)` | 拖入/选择/粘贴文件→变成 `@路径` 引用而非上传字节 | 上传字节 | `ui-conversation/src/client/apply.ts:113`；`preload-app.ts:110-112` | **P1（IDE 尤其重要）**；需从 JCEF 拖放事件取本地路径 |
| `__DSH_DIRECTORY_PICKER__.pick()` | 原生目录选择器建 workspace | Host 端 `uiWorkspace.pickDirectory()` | `ui-directory-picker-native/src/client/index.ts:24-27`；`preload-app.ts:104`；`directory-picker.ts` | **P1**；用 IDE `FileChooser`/`JFileChooser` |
| `dshDesktop.browser` | 侧栏 Browser 用 Electron webview：租约+独立持久分区 | 退回 iframe | `ui-sidebar-browser/src/client/index.ts:69-72`；`browser-guests.ts` | **P3**；JCEF iframe 已可用 |
| `dshDesktop.keyboard` + `dshDesktop.shortcuts` | 原生全局快捷键拦截/录制/关窗 | DOM 分发 | `shortcuts/src/client/index.ts:45`、`storage.ts:39`；`keyboard.ts`、`keybindings.ts` | **P3**；IDE 有自己的 keymap |
| `dshDesktop.deviceInfo()` | 反馈问卷附机器信息 | `navigator.userAgent` | `ui-settings-account/src/client/index.ts:215-217`；`device-info.ts` | **P3**；一个 JS 桥即可 |
| `dshDesktop.updates` | 设置页「更新渠道」卡片 | 隐藏 | `ui-settings-general/src/client/index.ts:106`；`preload-app.ts:68-85` | **不适用**；走 IDE 市场更新 |
| `__DSH_LOCALE__` | 首屏前用系统语言+已存偏好 | 只用 Host 设置 | `locale/src/client/bootstrap.ts`；`locale/src/client/index.ts:568`；`preload-app.ts:130-134` | **P2**；已用 patch 写 locale，可补 bootstrap |
| `dshOnboarding.hasApiKey()` | 桌面欢迎页引导 | 普通 Web 凭证页 | `ui-settings-account/src/client/onboarding-credentials.ts:9`；`preload-app.ts:90-93` | **P2**；IDE 无欢迎页，按普通 Web 走 |
| `dshPlatform` | 内嵌 usage/top-up 原生页 | Web 自己开窗口 | `ui-settings-account/src/client/index.ts:172`；`platform-view.ts` | **P2**；系统浏览器或 JCEF 子视图 |
| `dshDesktopBoot.ready()` / `__DSH_BOOT_READY__` / `__DSH_TRANSPORT__` | 壳 serve 前端时把启动注入+streamBaseUrl 交给 Web | 直接按 Host 服务页启动 | `apps/web/src/main.ts:17-36`；`client/web/src/boot.ts:59`；`webserver/src/injections.ts:96-118`；`web-document.ts:10` | 仅「自 serve 前端」时需要；直连 Host 不需要 |

> ⚠️ **设计陷阱**：`window.dshDesktop` 的「**存在性**」会翻转多处行为，不只看子成员：
> - `ui-settings-account/src/client/index.ts:48`：存在 → 走桌面 onboarding/账号分支；
> - `ui-settings-models/src/client/index.ts:82`：存在 → 隐藏普通凭证引导；
> - `ui-chat/src/client/apply.ts:171`：存在 → 默认 transcript view mode `standard`；
> - `product-analytics/src/client/index.ts:29`：存在 → 启用桌面埋点上报。
>
> 所以**不要为了几个子桥就盲目 `window.dshDesktop={protocolVersion:1}`**。更稳的路线：继续走
> 「自己的 Cordis client 插件精确覆盖服务 / 注入精确全局」（本仓库已在 `client.js` 覆盖 `sidebarRight.openResource`），
> 只补真正需要的，或只挂确实实现了的子桥并逐个评估 presence 开关副作用。

## 5. 对齐清单（按域）

### 5.1 页面桥 / Web 前端契约
见 §4。优先级建议：先做 `__DSH_DIRECTORY_PICKER__`、`__DSH_HOST_PATHS__`、`dshDesktop.fileActions`；
`dshDesktop.browser`/快捷键/deviceInfo 后置；`dshDesktop.updates` 不做。

### 5.2 账号 / 登录 / Platform 内嵌
- 官方：`welcome-window` PKCE 登录（`account-backend.ts`）、凭证走 `platform-session` 私有 IPC、
  `platform-view.ts` 内嵌持久分区页、`dshPlatform` 供前端开页。
- 本仓库：直接进 workspace；JCEF 里点「登录」走 `window.open`，很可能被 JCEF 拦或开成 JCEF 子窗；
  OAuth 回调到本机 `127.0.0.1` 由 Host 处理（同机可复用）。
- 要做（P1/P2）：JCEF 拦截 `window.open`/授权链接 → `Desktop.getDesktop().browse()` 系统浏览器；
  确认 Host PKCE 回调链路；usage/top-up 用系统浏览器或 JCEF 视图。

### 5.3 优雅退出与任务保护（高价值）
- 官方：退出前 `quit-inspection`（`quit-inspection.ts`）+ 确认框（`quit-confirmation.ts`）；
  更新前 `update-tasks lock`（`update-tasks.ts`）。
- 本仓库：`dispose()`/`stopInternal()` 直接 `taskkill /T /F`（`DshServerService.kt:370-421`），**不检查是否有 agent 在跑**。
- 要做（P1，见 `p0-quit-guard-and-recovery-plan.md`）：Host 侧提供任务检查接口，Kotlin 在
  停止/重启/修复/profile 同步前查询并弹确认；IDE 进程退出因 IntelliJ 无否决权，只能尽力提示。

### 5.4 诊断与恢复
- 官方：`crash-report.ts`（写 `crash-*.log`，保留 10 份）、`fatal-recovery.ts`（退出/重启/
  **禁用第三方插件+备份 `cordis.patch.yml` 后重启**/获取诊断）、`operation-journal/report`。
- 本仓库：只有 `FAILED` 状态卡 + 重试（`DshPanel.kt:150-156`），无崩溃文件、无「禁用第三方插件恢复」。
- 要做（P1）：启动/运行失败写诊断文件并提供入口；「禁用第三方 bundle + 备份 patch 后重启」
  （等价官方 `project-manager.ts:75-77` 的 `sanitizeProfile`）。已并入 P0 方案。

### 5.5 运行时与工具能力（决定 Office/数据分析是否可用）
- 官方 `primary-runtime`：独立 **Node + pnpm + Python(numpy/pandas/python-docx/python-pptx/openpyxl/
  Pillow/lxml/XlsxWriter) + office-skills + LibreOffice Kit**；`workspace-dependencies` 首次离线安装到
  `~/.dsh/dsh-runtimes/dsh-primary-runtime`（`desktop-host/src/office.ts`、`scripts/prepare-primary-runtime.ts`）。
- 本仓库：只有 Node24 + npm dsh；`idea` profile = base+web-app，**不含** `dsh-skill-office` /
  `dsh-tool-workspace-dependencies`（`sdk-app` 才含，`web-app` 不含）。
- 要做（P2，按需求）：若在 IDE 里也要 Office/文档/数据分析工具，需要给 `idea` profile 增加与
  `office.ts` 等价的组合（`skill-office` + `tool-workspace-dependencies`），并准备 Python 库与 LibreOffice Kit；
  否则明确「IDE 不支持 Office 类工具」。

### 5.6 不需要对齐（Electron/分发专有）
`update-*`（走 IDE 市场）、`tray`、`installer`/NSIS、签名/公证、`command-installation`、
`platform-view` 的 Electron 分区存储细节——这些在 IDEA 插件语境下无对应或价值极低。

## 6. 建议落地顺序

| 阶段 | 内容 | 理由 |
|---|---|---|
| **P0** | 退出任务检查+确认弹窗；崩溃/启动失败诊断文件；「禁用第三方插件+备份 patch 后重启」 | 防误杀、可排障，成本低收益高 |
| **P0/P1** | `__DSH_DIRECTORY_PICKER__`；`__DSH_HOST_PATHS__`；`dshDesktop.fileActions`（保存/远程图） | Web 交互直接可感；HOST_PATHS 对 IDE 拖入文件关键 |
| **P1** | JCEF `window.open`/登录授权外开 + 账号回调打通；`__DSH_LOCALE__` bootstrap | 账号能正常登录 |
| **P2** | Office 运行时/技能（按产品需要）；文件原生拖出；`dshPlatform` 内嵌页 | 能力补齐 |
| **P3** | 侧栏 Browser 真 webview、原生快捷键、deviceInfo | 边际收益 |

**架构建议**：
1. 保持「Host 半边 = desktop-host 职责、Kotlin/JCEF 半边 = desktop 职责」的切分，别把逻辑塞错边。
2. 页面桥优先用「自己的 client 插件精确覆盖/注入」，**谨慎整体设置 `window.dshDesktop`**。
3. origin 稳定性：现有「固定端口 + 快照」够用；若要彻底对齐官方可评估 self-serve 前端 + 自定义 scheme，
   成本高、收益主要是「端口再也不变」，可后置。

## 7. 关键源码依据

- Host 侧：`apps/desktop-host/src/index.ts:22-143`（启动/就绪/注入）、`:72-128`（IPC 控制）、
  `quit-inspection.ts:16-39`、`update-tasks.ts:16-61`、`file-drag.ts:25-63`、`platform-session.ts:10-35`、
  `office.ts:28-38`、`office-engine.ts:23-47`、`fatal-diagnostic.ts:21-58`、`cli.ts:13-26`。
- 壳侧：`apps/desktop/src/main.ts:262-368`（建窗）、`:371-604`（启动装配/后端）、`:787-803`（协议）、
  `:811-948`（preload IPC）；`web-document.ts:18-112`；`ipc.ts:12-47`；`preload-app.ts:16-134`；
  `file-actions.ts`、`file-drag.ts`、`browser-guests.ts`、`platform-view.ts`、`account-backend.ts`、
  `welcome-backend.ts`、`quit-confirmation.ts`、`crash-report.ts`、`fatal-recovery.ts`、`runtime-tree.ts`、
  `project-manager.ts`、`command-installation.ts`、`update-coordinator.ts`、`mandatory-update-policy.ts`。
- Web 前端契约：`packages/client/ui-file-actions/src/client/index.ts:25`、
  `ui-file-drag/src/client/index.ts:32-33`、`ui-directory-picker-native/src/client/index.ts:24-27`、
  `ui-conversation/src/client/apply.ts:113`、`ui-sidebar-browser/src/client/index.ts:69-72`、
  `ui-settings-account/src/client/index.ts:48,172,215-217`、`ui-settings-models/src/client/index.ts:82`、
  `ui-chat/src/client/apply.ts:171`、`product-analytics/src/client/index.ts:29`、
  `locale/src/client/bootstrap.ts`、`apps/web/src/main.ts:17-36`、`packages/client/web/src/boot.ts:59`、
  `packages/host/webserver/src/injections.ts:96-118`。
- 本仓库：`DshServerService.kt:104-221`（启动）、`:267-275`（命令）、`:370-421`（停止/杀树）；
  `DshPanel.kt:201-251`（桥注入）、`:286-319`（开文件）；`dsh-plugin-jetbrains/src/client.js`、`src/index.js`；
  `DshProfileBootstrap.kt:23-75`；`DshBundleBootstrap.kt:56-135`。
