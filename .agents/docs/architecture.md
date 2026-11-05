# DSH for JetBrains — 架构

## 1. 目标与取向

在 JetBrains IDE 里使用 DeepSeek Harness，但**不另起一套数据**：

| 目标 | 做法 |
|---|---|
| 复用官方客户端的数据 | 不设置 `DSH_HOME`，直接用 `~/.dsh`：凭据、设置、会话历史与桌面端完全共享 |
| 全 IDE 单实例 | 应用级单例，一个 `dsh` 进程服务所有项目窗口 |
| 项目区分 | URL 参数 `ideWorkspace=<项目绝对路径>`，不按项目起进程 |
| origin 稳定 | 固定端口（默认 45231，被占用向上探测），避免 localStorage 丢失 |

这与 `deepseek-harness-for-ide` 的取舍**相反**：那个插件"一项目一进程 + 每项目隔离 home"，
换来零跨项目污染，代价是 N 份运行时、N 个 Node 进程、以及桌面端的会话在 IDE 里看不到。

## 2. 源码依据（决策的地基）

以下结论来自 `E:\github\deepseek-harness`（官方源码，master `8bd073392e`，0.2.2-alpha.3）。

### 2.1 单进程多工作区是官方支持的

- workspace 是持久实体：`{id: UUID, path: realpath 绝对目录, title, sessionIds, createdAt, updatedAt}`
  （`packages/workspace/workspace/src/spec.ts:24-84`、`types.ts:69-153`）
- 会话创建时把 cwd 写进**不可变 header**：`cwd = workspace.path ?? request.cwd ?? defaultCwd`
  （`packages/api/session-controller/src/commands.ts:116`、`agent.ts:432-452`）
- 工具执行**不看进程 cwd**，取该会话的 workingDirectory
  （`packages/fs/tool-fs/src/session-cwd.ts:21-24`、`packages/shell/tool-bash/src/index.ts:488`）
- 会话日志按 cwd 分目录：`sessions/--<cwd>--/<id>/session.vN.*`
  （`packages/session/session-persistence-jsonl/src/format.ts:254-268`）
- 官方桌面端本身就是单进程多 workspace 用法

**结论**：一个进程同时服务 N 个项目成立，不需要每项目一个进程。

### 2.2 共享 home 的安全性

| 资源 | 并发保护 | 结论 |
|---|---|---|
| `sessions/` JSONL | 跨进程 lease（flock / 命名信号量），第二写者抛 `SessionAlreadyOwnedError`；append-only + fsync | 安全 |
| `.credentials.yaml` | `<file>.lock` + 原子替换（`packages/util/atomic-write/src/index.ts`） | 安全 |
| `profiles/*/package.json`、插件安装 | `package.json.lock` 串行 | 安全 |
| `storages/` 的 `workspace.json`、`schedule` | **whole-unit 整文件重写，last-write-wins** | ⚠️ 可能丢更新 |
| `session-query-sqlite` | 单进程独占（当前配置 `:memory:` + `openAt: never`，未落盘） | 无冲突 |

**结论**：会话与凭据可以放心共享；只有 workspace 列表在与桌面端**同时运行**时可能互相覆盖，
所以启动时检测桌面端并提示（`DshServerService.isDesktopRunning`），不阻塞。

### 2.3 UI 没有深链参数（需要自己补）

- 客户端只读 `?token=` / `?interactions=`（`packages/client/connection/src/browser-auth.ts:17,246,266`）
- 初始工作区恢复顺序：localStorage 当前 sessionId → 其 workspace → `recentWorkspace()`（按会话 `updatedAt`）
  （`packages/client/ui-workspace/src/client/navigation.ts:465-495,577-596`）

**结论**：单进程多项目时，UI 默认落到"最近聊过的项目"。
要精确打开当前 IDEA 项目，必须注入客户端扩展读取 `ideWorkspace`。

**已实现（2026-11-04）**：内置 `dsh-ide-client` 包 + 独立 `dsh-ide-client.patch.yml` 覆盖层
（`dsh --patch` 挂载），见 HANDOFF §3.7。插件不改写 profile 的 `cordis.patch.yml`——
DSH 设置编辑器会把主题/语言等条目追加到该文件末尾，早期用标记段维护时会被下一次启动误删。
注意参数必须放 **fragment**：带 `?token=` 首开时 DSH 用 303 `Location: ./` 换 cookie，
query 会被整体丢弃；fragment 按 RFC 7231 继承，实测 Chromium 成立。

### 2.4 profile 是分开的

- `PROFILE_TEMPLATES` 只有 `web / desktop / acp / headless / sdk*`（`packages/util/official-bundles/src/bundles.ts:10-27`）
- 自定义 profile 名没有模板，`loadProfile` 目录不存在会直接抛错
  （`packages/boot/app-boot/src/profile.ts:730-741`）→ 插件必须自己建目录，内容等价 `initProfile`
- `desktop` profile 被 CLI 硬性拒绝 boot（`apps/cli/src/args.ts:84-85`）
- `web` 是 `--profile web` 的语法糖；自定义 profile 直接 `--profile <name>` + web 参数，
  **不能**再跟 `web`（会报 `too many arguments`）

**结论**：IDE 默认用 `idea` profile，与桌面端插件集互不影响，同时共享 sessions/storages/凭据。
（profile 名历史上是 `dsh-ide`，迭代 2 改为 `idea`；`DshSettings.loadState` 会把历史值自动迁移。）
若本机存在 `profiles/web` → `profiles/desktop` 的 junction，也可把设置里的 profile 改成 `web`，
让 IDE 复用桌面端的插件与主题配置；代价是两边不能同时运行（共写同一个 profile）。

## 3. 进程与数据

```
IDE 进程（applicationService 单例 DshServerService）
└─ dsh --profile idea --patch <profile>/dsh-ide-client.patch.yml --host 127.0.0.1 --port 45231 --no-open
     DSH_HOME 未设置 → ~/.dsh                    ← 与桌面端共享
     ├─ ~/.dsh/.credentials.yaml                （共享，有锁）
     ├─ ~/.dsh/sessions/                        （共享，lease 保护）
     ├─ ~/.dsh/storages/workspace.json          （共享，last-write-wins）
     └─ ~/.dsh/profiles/idea/                   （IDE 专属：bundles + 插件 + patch）

JCEF 面板（每项目一个）
└─ http://127.0.0.1:45231/?token=...#ideWorkspace=<URL 编码的绝对路径>
     （fragment 不会被 303 换 cookie 丢弃；客户端补丁优先读 hash、回退 query）
```

### 3.1 命令解析 / 设置页 / 通知（迭代 2）

**命令解析**（`DshCommandResolver`，纯函数、只读文件系统）顺序固定：

1. 显式配置（非空白且不是 `dsh` / `dsh.exe` / `dsh.cmd`）；
2. PATH（Windows 按 `dsh.cmd` → `dsh.exe` → `dsh.bat` → `dsh`，非 Windows 要求可执行位）；
3. 桌面端已知 runtime `D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd`；
4. `Missing`（message 含已查找路径 + Settings 提示）。

Windows 非法路径字符（`| : ? * < >`、NUL）在解析器内部被 `runCatching` 捕获，按 `Missing` 处理，
不会抛 `InvalidPathException`；PATH 里的坏条目只跳过该条目、继续查找，只有最终找不到才返回 `Missing`。
未预期异常（如 `Files.isRegularFile` 在极端环境抛 `SecurityException`）由 `DshServerService` 外层
`runCatching` 转 `FAILED`，避免 lifecycle 线程吞异常后卡在 `STARTING`。相对路径按
`user.home` 解析，与子进程 `ProcessBuilder.directory` 保持一致。

**设置页**：`Settings → Tools → DSH`（`applicationConfigurable`，id `dev.doyzheng.dsh.settings`）。
字段：dsh 命令 / 监听地址 / 端口（1..65535）/ profile / autoStart / warnWhenDesktopRunning；
端口非法在 `apply()` 抛 `ConfigurationException`。设置应用级，**变更对下次启动生效**，
重启路径按状态分三种：

1. **`FAILED`（主路径）**：点工具窗口状态卡下方的「重试」按钮，重新调用 `startAsync()`；
2. **`STOPPED` / 工具窗口 content 重建（重开项目或 IDE）**：`onShown()` 只在
   `DshToolWindowFactory.createToolWindowContent` 创建 content 时调用，会重新启动；普通显示/隐藏不保证；
3. **`RUNNING`**：没有运行中重启入口，改设置后需重启 IDE（或重启 dsh 服务）。

`autoStart` 只控制项目打开时**自动展示工具窗口**，不控制服务启停：服务由工具窗口 content 创建时的
`DshPanel.onShown()` 在 `STOPPED`/`FAILED` 状态下启动，普通显示/隐藏不会重复触发。

**失败重试**：`DshPanel` 仅在 `FAILED` 状态卡下方展示「重试」按钮，点击只调用 `DshServerService.startAsync()`
（异步 lifecycle 线程执行，不在 EDT 做 I/O），随后由 `STARTING` 状态刷新界面；按钮在 `STARTING`/`STOPPED`/`RUNNING`
均不展示。`RUNNING` 中改设置没有运行中重启入口，需重启 IDE。

**通知**：通知组 `DSH`（`displayType="BALLOON"`），`DshNotifier` 用 `AtomicBoolean` 保证每个 IDE
进程只提示一次；只在桌面端运行时提示 workspace 列表可能互相覆盖，不影响启动。

## 4. 与 zcode-ide 的共同点

- JCEF 内嵌官方 Web UI，不自研前端
- 应用级单例 + 固定端口（localStorage 稳定）
- 项目通过 URL 参数区分，而不是每项目一个服务
- Windows 用 `taskkill /T /F` 杀进程树

## 5. 待实现（按优先级）

1. ✅ **客户端补丁 `dsh-ide-client`**（已完成）：内置 npm 包拷贝进
   `~/.dsh/profiles/<profile>/node_modules/`，写 `<profile>/dsh-ide-client.patch.yml` 覆盖层，
   启动命令用 `--patch` 挂载；读 fragment 的 `ideWorkspace`，`workspaces.create` 幂等登记 +
   `uiWorkspace.openWorkspace` 选中。
2. ~~**workspace 注册**~~（已取消）：由 1 覆盖，不再在 Kotlin 侧实现 Typert RPC。
3. ✅ **命令解析**（已完成）：`DshCommandResolver`，顺序与异常兜底见 3.1。
4. ✅ **设置页**（已完成）：`Settings → Tools → DSH`，dsh 命令 / 监听地址 / 端口 / profile /
   自动启动 / 桌面端提示开关；端口非法在 `apply()` 拒绝保存。
5. ✅ **并发提示**（已完成）：通知组 `DSH`，每个 IDE 进程只提示一次，不阻塞启动。
6. ✅ **官方图标**（已完成）：插件列表 `META-INF/pluginIcon.svg`（40×40）+
   工具窗口 `icons/dsh-toolwindow.svg`（16×16）。
7. ✅ **W→IDE 桥（0.1.5）**：`DshPanel` 用 `JBCefJSQuery` 注入 `window.__dshIdeOpenFile`；
   客户端补丁**覆盖 `ctx.sidebarRight.openResource` / `openResourceIn`**（所有「打开文件」的入口），
   文件资源按会话 cwd 解析后交给 Kotlin，用 `OpenFileDescriptor` 打开、定位行（1-based → 0-based）；
   `changes-review` 地址先取该轮摘要再打开对应文件。桥不存在时保持 DSH 默认侧栏预览。IDE 原生 Diff 未做。
8. ✅ **IDE→对话桥（0.1.6）**：项目树 / 编辑器 Tab / 编辑器右键把文件/目录引用、编辑器选区
   （只发路径与起止行）经 `DshIdeService`（项目级排队）→ `DshPanel.pushToWebview` →
   `window.__dshIdePush` → client.js `__dshIdeInsertRefs` 转成官方 `@` 选择器同款结构化 chip
   插入输入框末尾（`conversation.input.for(actx).insertReference`）。插入点固定为草稿末尾
   （未聚焦时 `captureInsertion` 返回陈旧选区）；`TokenSpan` 是 detect 坐标（chip 占 1 个
   U+FFFC），追加位置按 occurrences/claim 从 clipboard 长度折算。选区拼成 `@路径 (L12-L30)`。
9. **插件同步**：把桌面端 `profiles/desktop` 的第三方插件清单单向同步到 `profiles/idea`
   （可选，按需再定）

## 6. 已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| workspace.json 覆盖 | 与桌面端同时运行会 last-write-wins | 启动检测 + 提示 |
| profile 插件集不一致 | 桌面端装的插件在 IDE 里不生效 | 后续做单向同步 |
| 客户端补丁依赖 bundle 结构 | 上游改前端会破坏补丁 | 构建期断言 + 锁版本 |
| 303 丢 query | token 换 cookie 的 303 会丢弃 query 参数 | `ideWorkspace` 放 fragment（RFC 7231 继承） |
| 端口被占用 | 改变 origin 会丢 localStorage | 向上探测并写回设置，尽量稳定 |
| 桌面端 runtime 兜底路径硬编码 | 解析器最后一级固定为 `D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd`；非 Windows 或非本机安装会落到 `Missing` | 在 Settings 指定命令，或把 dsh 加入 PATH |
| 桌面端检测异常 | `ProcessHandle.allProcesses()` 在受限环境可能抛异常；异常会中断启动流程，状态可能停在 `STARTING` | 调用处 `runCatching` 兜底，异常视为「未运行」，只影响提示不阻塞启动 |
| 旧 IDE 重建 `dsh-ide` + 45231 冲突 | 运行参考插件 `deepseek-harness-for-ide` 的旧 IDE 会自动重建空的 `~/.dsh/profiles/dsh-ide` 并占用 45231；本插件默认端口同样是 45231 | 同开时向上探测到 45232，origin 变化会丢 localStorage；T4 实机前关闭旧 IDE / 参考插件，长期共存则改默认端口 |
| profile 改名迁移 | 默认 profile 由 `dsh-ide` 改为 `idea` | `DshSettings.loadState` 自动迁移历史值；真实 `profiles/idea` 已迁移，`DshSettingsTest` 覆盖迁移 |

## 7. 真正的 DSH 插件（`dsh-plugin-jetbrains`，0.2.0）—— 历史方案，已被 §8 取代

项目此前只有「IDE 启动时注入的客户端补丁」（`dsh-ide-client`）。0.2.0 起把管理能力做成
**真正的 Cordis 插件**，与 Kotlin 的 IDEA 插件（名 `dsh-idea-plugin`）组成两个半边。

### 7.1 分层与身份

| 半边 | 目录 | 身份 | 运行位置 |
|---|---|---|---|
| DSH 插件 Host + Client | `dsh-plugin-jetbrains/` | npm 包 `dsh-plugin-jetbrains` | DSH 的 Node 宿主进程 + 浏览器页面 |
| IDEA 插件 | `src/main/kotlin/`、`META-INF/` | plugin name `dsh-idea-plugin`，id `dev.doyzheng.dsh-ide` | JetBrains IDE 进程 |

- 包内 `package.json` 同时声明 `dsh.bundle.patch`（插入 Host 行）与 `dsh.client`
  （`platform: web`，导出 `./client`），是官方推荐的「Host + 浏览器半边」形态。
- Client 模块 id 必须等于包名（`window.__ModuleLoader__.load({ id: 'dsh-plugin-jetbrains' })`）。
- IDEA 插件 id 保持 `dev.doyzheng.dsh-ide` 不变：只改显示名/产物名，避免 IDE 把升级当成新插件
  而残留旧版本；旧的 `plugins/dsh-ide` 目录由安装逻辑备份后清理。
- **0.2.0 方案 2**：原先由 Kotlin `DshClientPatch` 注入的 `dsh-ide-client` 浏览器补丁已并入
  `dsh-plugin-jetbrains` 的 Client 半边（`client.js` 的 A 段）。
- **0.2.1 更稳兜底**：Kotlin 把 `dsh-plugin-jetbrains` 打进 IDE 插件 jar 的 `/dsh-plugin-jetbrains`
  资源，每次启动由 `DshBundleBootstrap` 注入当前 profile：拷贝到
  `<profile>/node_modules/dsh-plugin-jetbrains`；若该 profile 的 `dsh.profile.bundles` 已含插件，
  走 bundle 加载（不 `--patch`，避免重复 id），否则写 `<profile>/dsh-plugin-jetbrains.patch.yml`
  并用 `--patch` 挂载。这样引用/复制/新建的 profile 都不会再缺 DSH 插件。
  同时 Host 侧 `applyProfile/restoreProfile` 的 `ensureIdeBundle` 仍会补 `node_modules` 链接与
  `package.json` 的 dependencies/bundles（双保险）。

### 7.2 Host ↔ Client 通信（为什么不生成 Typert 代码）

纯 JS 插件没有构建期 Typert codegen。Host 半边用 `ctx.webServer.register({ kind:'prefix', path:'/dsh-ide' })`
注册一个 HTTP 前缀路由，Client 半边用同源 `fetch('/dsh-ide/...')` 调用。
每个请求先过 `ctx.get('connection').requestRejection(req)`（Host/Origin 栅栏 + 登录 cookie），
与官方 `open-in-app` 一致；无 `connection` 服务时退化为仅本机回环可用。

路由：

| 方法 | 路径 | 能力 |
|---|---|---|
| GET | `/dsh-ide/state` | IDE 列表（含 running / installed）+ profile 列表 + 插件包信息 |
| GET | `/dsh-ide/profile/backups` | profile 备份列表 |
| POST | `/dsh-ide/ide/install` | 关闭 IDE → 备份 → 解压安装 → 可选启动 |
| POST | `/dsh-ide/ide/uninstall` | 关闭 IDE → 备份 → 删除 |
| POST | `/dsh-ide/ide/process` | 单独启动/关闭 IDE |
| POST | `/dsh-ide/profile/apply` | reuse / copy / empty |
| POST | `/dsh-ide/profile/restore` | 从备份切回 |

### 7.3 JetBrains 检测

不读注册表（本机 `HKLM\SOFTWARE\JetBrains` 有 WebStorm 残留但未安装，会误报）。
改为枚举候选安装根（`Program Files\JetBrains`、`Program Files (x86)\JetBrains`、
`%LOCALAPPDATA%\Programs`、JetBrains Toolbox `apps/<product>/<channel>/<build>`），
目录里存在 `product-info.json` 才认定为真实安装，取其 `name/version/buildNumber/dataDirectoryName`
与 `launch[].launcherPath`（解析出 `bin\idea64.exe`）。配置目录 =
`%APPDATA%\JetBrains\<dataDirectoryName>`，插件目录 = `<config>/plugins`。

运行状态：`Get-Process | Select Id,ProcessName,Path`，按「可执行路径相等」或「安装目录前缀」匹配。
关闭：先 `CloseMainWindow` 优雅退出、每 3s 补发，超时按 `force` 用 `Stop-Process -Force` 兜底。

### 7.4 Profile 管理

设置页用 **Tab** 呈现两种用法：**引用**（reuse）与 **复制**（copy）。
`profiles/idea` 可以是：真实目录（独立）、指向 `desktop`/`web` 的 junction（复用）。
- **引用 Tab**：选择来源 `desktop`/`web`，把 `idea` 链接过去（`fs.symlink(realpath(source), idea, 'junction')`）；
- **复制 Tab**：选择来源与要复制的内容（`package.json` / `cordis.patch.yml` / `pnpm-*` / `node_modules` /
  `.plugin-manager` / `cache`），点「同步」→ 先复制到 `idea`，成功后通过桥重启宿主 Web；
- Host 还支持 `empty`（写入与 `initProfile()` 等价的默认三件套），当前 UI 未暴露；
- 每次替换前把当前 profile（目录→rename，链接→记录 target 到 `link.txt`）备份到
  `~/.dsh/dsh-ide/profile-backups/<target>-<ts>`，**restore** 可切回。
- **强制保证插件存在**：`applyProfile` / `restoreProfile` 结束后统一调 `ensureIdeBundle(targetDir)`——
  目标 profile 缺 `node_modules/dsh-plugin-jetbrains` 就建目录链接（junction）指向当前插件包，
  并在 `package.json` 的 `dependencies`、`dsh.profile.bundles` 补齐（幂等）。因此「引用/复制/空」
  三条路建出的 idea profile 一定装得下 DSH 插件；引用模式 target 就是来源 profile，写入会落到
  desktop/web，这是 IDE 能加载 DSH 插件的前提。

### 7.5 重启宿主 Web

DSH 插件运行在 IDE 托管的 dsh 进程里，无法干净重启自己。Kotlin `DshPanel` 在 `onLoadEnd`
注入 `window.__dshIdeRestartWeb = function(reason){...}`（JBCefJSQuery），
设置页检测到该函数才显示按钮，调用后 Kotlin 执行 `DshServerService.restartAsync()`。
普通浏览器（桌面端）没有该桥，界面显示提示。

### 7.6 已知取舍

- 安装 DSH 插件会触发 profile 的 `pnpm install`，pnpm 会把它不认识的 `dsh-ide-client`
  （由 Kotlin 运行时拷贝进 `node_modules`）挪到 `.ignored/`；Kotlin 在每次 IDE 启动时会重新拷贝，
  但桌面端 profile 在插件安装后被影响了的话需手动/重启恢复。当前已在安装后手工恢复。
- 设置页 UI 的视觉（图标、暗色主题）与「重启宿主 Web」实机行为仍需人工在 IDE 内确认。
- 以上 0.2.x 的「DSH 设置页」方案已被 §8 取代。

## 8. 以 IDEA 插件为主（管理功能迁到 IDE 设置页）

产品定位改为「以 IDEA 插件为主发布」后，**全部管理能力从 DSH 设置页迁到 IDEA 插件**，
DSH 插件退化为纯 IDE 桥。二者职责重新划分：

| 部件 | 目录 | 职责 |
|---|---|---|
| IDEA 插件（Kotlin，`dsh-idea-plugin`） | `src/main/kotlin/`、`META-INF/` | 托管 DSH Web（JCEF）+ **全部管理功能**：`Settings → Tools → DSH` |
| DSH 插件（Cordis，`dsh-plugin-jetbrains`） | `src/main/resources/dsh-plugin-jetbrains/` | **仅** IDE 桥（`client.js`）+ 运行期 `ensureIdeBundle`（`index.js`）；构建时随 resources 打进 jar 的 `/dsh-plugin-jetbrains` |

### 8.1 IDEA 设置页（Kotlin）

- `settings/DshSettingsConfigurable.kt`：启动设置（dsh 命令 / host / port / profile / 自动展示）+ 嵌入管理面板。
- `settings/DshManagementPanel.kt`：Swing 面板——JetBrains 工具列表（安装/更新/卸载/启停）、
  Profile 管理（复用 junction / 选择性复制 / 空 profile + 备份切回）、重启 DSH 服务。
  所有耗时动作在后台线程执行，完成回 EDT 刷新；破坏性操作先二次确认。
- 后端：
  - `jetbrains/JetBrainsDetector.kt`：枚举候选安装根、读 `product-info.json`、算配置/插件目录、读已装插件版本；
    运行状态用 `ProcessHandle`（不再走 PowerShell Get-Process）。
  - `jetbrains/IdePluginInstaller.kt`：**安装源=当前插件自己的目录**（`plugins/<dir>/lib` 下的 jar），
    关 IDE（`CloseMainWindow`→超时 `Stop-Process -Force`）→ 备份 → 复制到目标 `plugins/dsh-idea-plugin` → 可选启动。
    不再依赖打包 zip。
  - `jetbrains/ProfileManager.kt`：reuse/copy/empty + restore，备份到 `~/.dsh/dsh-ide/profile-backups/`；
    应用后调 `DshBundleBootstrap.ensureInstalled()` 强制 profile 装 `dsh-plugin-jetbrains`。
  - `jetbrains/Fs.kt`：PowerShell/cmd 封装、junction 探测与创建（Windows 用 `New-Item -ItemType Junction`）、目录复制/删除。
  - `DshBundleBootstrap.kt` 新增 `ensureInstalled()`：拷贝 jar 内插件资源到 `node_modules/dsh-plugin-jetbrains`
    并把 `dependencies`（`link:` 指向该目录）与 `dsh.profile.bundles` 写进 `package.json`。

### 8.2 DSH 插件精简

- `src/index.js`：空入口（`export const name` + 空 `apply`）。不再有任何宿主逻辑——profile 注入由 Kotlin
  `DshBundleBootstrap` 每次 IDE 启动完成；删除了 JetBrains 检测、安装/卸载、profile apply/restore 及
  `/dsh-ide/*` HTTP 路由。
- `src/client.js`：删除 `settings.section` 设置页与所有 CSS/翻译/`api()`，只保留 IDE 桥（A 段）。
- `package.json` / `cordis.patch.yml` / `README.md` 同步更新（`src/` 只放 `index.js`、`client.js`）。

### 8.3 为什么这样更合理

- **启动顺序**：profile 必须在 DSH 启动前选好，DSH 内部设置页天然做不到；只有 IDE 设置能管理 profile 来源。
- **消灭循环依赖**：旧方案里「装 IDEA 插件」要先进 DSH 设置页，而进设置页又得有 IDEA 插件把 DSH 跑起来。
- **单一入口**：用户在 IDE 里装插件，自然去 `Settings → Tools → DSH`。
- **自足**：IDEA 插件不再需要 DSH 运行、也不需要额外 zip 就能检测/安装/管理 profile。

