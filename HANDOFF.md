# HANDOFF — DSH for JetBrains

> 交接文档。读完这一篇就能接手；设计细节见 [`.agents/docs/architecture.md`](.agents/docs/architecture.md)。
> 协作规则见 [`AGENTS.md`](AGENTS.md)（**能直接做的操作一律自己做，不要让用户手动操作**）。
> 最后更新：2026-11-05（**管理功能全量迁到 IDEA 插件 `Settings → Tools → DSH`**：检测 JetBrains / 安装卸载本插件 / profile 管理 / 重启 DSH 服务都由 Kotlin 实现；DSH 插件 `dsh-plugin-jetbrains` 只保留 IDE 桥（`client.js`）+ 空的 Host 入口（`index.js`），删除其 `settings.section` 与 `/dsh-ide` 路由。0.2.1：IDEA 插件每次启动把 `dsh-plugin-jetbrains` 注入当前 profile）

## ★ 本次变更（以 IDEA 插件为主：管理功能进 IDE 设置页）

产品定位改为「以 IDEA 插件为主发布」，因此**全部管理能力从 DSH 设置页迁到 IDEA 插件**：

| 能力 | 现在的位置 |
|---|---|
| 检测本机 JetBrains 工具 | IDEA 插件 `Settings → Tools → DSH`（Kotlin `jetbrains/JetBrainsDetector`） |
| 安装 / 更新 / 卸载本插件到其它 IDE | 同上（`jetbrains/IdePluginInstaller`；安装源=当前插件目录，关→备份→复制→可选启动） |
| Profile 管理（引用/复制/空 + 备份切回） | 同上（`jetbrains/ProfileManager`；应用后强制 ensure `dsh-plugin-jetbrains`） |
| 重启 DSH 服务 | 同上（`DshServerService.restartAsync()`） |
| IDE 桥（ideWorkspace / 打开文件 / 插入引用 / 侧栏过滤） | DSH 插件 `client.js`（不变） |

DSH 插件 `dsh-plugin-jetbrains` 的 `index.js` 已精简为**空入口**（无宿主逻辑，profile 注入由 Kotlin `DshBundleBootstrap` 完成）；`client.js` 只留 IDE 桥；`/dsh-ide/*` 管理路由与 `settings.section` 全部移除；`scripts/selftest.mjs` 已删除（无 JS 逻辑可测）。

---

## 以下为 0.2.0 / 0.2.1 历史记录（DSH 设置页方案，已被上面取代）

## ★ 新增：真正的 DSH 插件（`dsh-plugin-jetbrains`，0.2.0）


项目从「只在 IDE 启动时注入的 DSH 客户端补丁」升级为**两个半边**：

| 半边 | 位置 | 身份 |
|---|---|---|
| DSH 插件（Host + Client，纯 JS/Cordis） | `src/main/resources/dsh-plugin-jetbrains/` | 包名 `dsh-plugin-jetbrains`；安装进 DSH profile（0.2.x 时在仓库根，现移入 IDEA 插件 resources） |
| IDEA 插件（Kotlin） | `src/main/kotlin/`、`META-INF/` | 插件名 `dsh-idea-plugin`（id 仍是 `dev.doyzheng.dsh-ide`，避免升级时被当成新插件） |

新增能力（对应需求 1–6）：

1. **设置界面**：Client 半边注册 `settings.section`（id `dsh-ide`，order 40），在「设置 → DSH IDE 集成」。
2. **检测 JetBrains 工具**：Host 半边扫描 `Program Files\JetBrains`、`%LOCALAPPDATA%\Programs`、
   JetBrains Toolbox 布局，读 `product-info.json`（不靠注册表）；本机只检测到 IntelliJ IDEA 2026.2.3。
3. **强制安装**：检测进程 → 优雅关闭（超时强杀）→ 备份旧插件 → 解压 `assets/dsh-idea-plugin.zip` 到
   `<config>/plugins/dsh-idea-plugin` → 可选启动 IDE。改名前的 `plugins/dsh-ide` 会自动备份并清理。
4. **卸载**：关闭 IDE（force 兜底）→ 备份 → 删除插件目录。
5. **Profile 管理**：把 `idea` profile 复用为 `desktop`/`web` 的**目录链接（junction）**、
   从它们**选择性复制**（package.json / cordis.patch.yml / pnpm-* / node_modules / .plugin-manager / cache）、
   或**创建空 profile**；每次替换前备份到 `~/.dsh/dsh-ide/profile-backups/`，设置页可一键**切回**。
6. **重启宿主 Web**：Kotlin `DshPanel` 注入 `window.__dshIdeRestartWeb(reason)`，设置页调用它 →
   `DshServerService.restartAsync()` 重启 IDE 托管的 dsh web 进程（普通浏览器里没有该桥，界面会提示）。
7. **IDE 桥并入 Client 半边（0.2.0）+ Kotlin 兜底注入（0.2.1）**：`ideWorkspace` 选中项目、
   点文件在 IDEA 打开、IDE 右键插入 `@引用`、侧栏只显示当前项目，全部在 `dsh-plugin-jetbrains/client.js`。
   **0.2.1 起** Kotlin 把 `dsh-plugin-jetbrains` 打进 IDE 插件 jar 的 `/dsh-plugin-jetbrains` 资源，
   每次启动由 `DshBundleBootstrap` 注入当前 profile：拷贝到 `node_modules/dsh-plugin-jetbrains`，
   profile 的 `dsh.profile.bundles` 已含则走 bundle，否则写 `dsh-plugin-jetbrains.patch.yml` 并 `--patch`。
   因此引用/复制/新建 profile 都不会缺插件；Host 侧 `ensureIdeBundle` 另做依赖/bundles 双保险。

Host 通过 `ctx.webServer` 注册 `/dsh-ide/*` 路由（HTTP + 同源 cookie 校验，`connection.requestRejection`），
Client 用同源 `fetch` 调用；不使用 Typert 生成代码，纯 JS 可维护。详见
[`dsh-plugin-jetbrains/README.md`](src/main/resources/dsh-plugin-jetbrains/README.md)。

**验证状态**：Host 自检 `node src/main/resources/dsh-plugin-jetbrains/scripts/selftest.mjs` 全绿（真实检测 / 临时 home 的
profile 全流程 / 真实 zip 的安装卸载 / 路由装配与鉴权）；Kotlin `buildPlugin` + `test` 通过（31 条，已删除
`DshClientPatchTest` 的 8 条）；bundle 已装进本机 `desktop` profile（`plugin_manager` → `applied`），
活页面 `settings.section` 已出现 `dsh-ide` 条目；IDEA 侧已装 `dsh-idea-plugin` 0.1.8（旧 `dsh-ide` 已备份清理）。
**设置页视觉、IDE 桥与「重启宿主 Web」实机行为仍待人工在 IDE 内确认。**

## 0. 一句话

给 JetBrains IDE 做一个 DSH 插件：**全 IDE 一个 dsh 进程、共享桌面端同一份 `~/.dsh` 数据、项目靠 URL 参数区分**。
和 `deepseek-harness-for-ide`（一项目一进程 + 每项目隔离 home）是相反的取舍。

## 1. 当前状态

| 项 | 状态 |
|---|---|
| Gradle 工程 + 构建 | ✅ `buildPlugin` 通过（约 63 KB zip），32 个单测全绿 |
| 进程托管（单例、固定端口、进程树清理） | ✅ 代码完成，未在 IDE 里跑过 |
| profile 自动创建（`idea`） | ✅ 代码完成；客户端包与 patch 注入已用临时 home 实测；真实 `~/.dsh/profiles/idea` 已迁移 |
| JCEF 面板 + `ideWorkspace` 参数 | ✅ 代码完成；URL 走 fragment（见 3.7），未实机验证 |
| **客户端补丁（读 `ideWorkspace` 选中项目）** | ✅ 代码 + 单测 + 真实 runtime 浏览器冒烟通过；**待 JCEF 实机** |
| workspace 注册 / 项目切换 | ✅ 由客户端补丁完成（`workspaces.create` 幂等 + `uiWorkspace.openWorkspace`） |
| 项目打开自动展示工具窗口 | ✅ `DshStartupActivity`（受 `autoStart` 控制），未实机 |
| 设置页（Settings → Tools → DSH） | ✅ `DshSettingsConfigurable`；端口非法在 `apply()` 抛 `ConfigurationException`，未实机 |
| 命令解析（配置 → PATH → 桌面端 runtime） | ✅ `DshCommandResolver` + 16 条单测 |
| 桌面端并发提示 | ✅ `DshNotifier` 通知组 `DSH`，每个 IDE 进程只弹一次，未实机 |
| 官方图标 | ✅ 插件列表 `META-INF/pluginIcon.svg`（40×40）+ 工具窗口 `icons/dsh-toolwindow.svg`（16×16） |
| profile 默认值 `idea`（含历史值迁移） | ✅ 默认 `idea`；`loadState` 把历史值 `dsh-ide` 迁移为 `idea` |
| W→IDE 桥（开文件 / 定位行） | ✅ 0.1.5 覆盖 `openResource`/`openResourceIn`，实机验证通过；IDE 原生 Diff 未做 |
| IDE→对话桥（右键发送文件/选区） | ✅ 0.1.6 已实现并实机验证：项目树/编辑器 Tab/编辑器右键 → `@路径` 引用插入输入框 |
| **`runIde` 实机验收** | ⏳ 待验收（发布前门） |

结论：**P0 与迭代 2 开发侧闭环，差 `runIde` 实机验收**。实机通过前不宣称可用；插件版本已升 `0.1.6`（含双向桥：DSH 点文件在 IDEA 打开 + IDEA 右键把文件/选区发进 DSH 输入框），仍待 `runIde` 实机验收。

## 2. 环境与构建

```bash
# JDK（必须 21）
export JAVA_HOME="D:\\apps\\jdk\\jdk-21.0.2"

cd /e/gitee/doyzheng/dsh-ide
./gradlew compileKotlin --no-daemon     # 编译
./gradlew test --no-daemon              # 31 条单测
./gradlew buildPlugin --no-daemon       # → build/distributions/dsh-idea-plugin-0.1.9.zip
./gradlew runIde --no-daemon            # 沙箱 IDE（首次要下整包 IDE，慢）
```

测试构成（0.2.0 起）：`DshCommandResolverTest` 16 + `DshComposerMessageTest` 6 + `DshSettingsTest` 4 + `DshWebUrlTest` 5 = 31。
（`DshClientPatchTest` 8 条已随 Kotlin 客户端补丁注入的移除而删除。）
`DshCommandResolverTest` 覆盖解析顺序、Windows 非法路径字符与缺失提示；`DshSettingsTest` 覆盖
默认 profile `idea` 与历史值迁移；`DshComposerMessageTest` 覆盖 IDE→对话消息的 JSON 契约。

依赖坐标：IntelliJ Platform Gradle Plugin `2.2.1` + Kotlin `2.1.0` + IC `2025.1`（`gradle.properties` 可改）。

### 运行时来源（重要）

本机**没有**独立安装的 `dsh`（PATH 里没有）。但：

```
D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd   → 0.2.1-rc.5，可直接当通用 CLI
```

实测可用：

```bash
cmd.exe /c "D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd --version"   # 0.2.1-rc.5
```

它就是桌面端 Electron 的 `RunAsNode` 包装（内部跑 `app.asar/.../dsh-desktop-host/lib/cli.js`），
但**接受自定义 profile 并正常起 web 服务**，所以插件不需要内置 runtime，直接复用这一份。

`DshSettings.dshCommand` 默认 `dsh`，解析顺序已由 `DshCommandResolver` 实现：
显式配置 → PATH → `D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd` → `Missing`
（message 含已查找路径与 Settings 提示）。找不到或路径非法都只报错，不启动进程。

### 官方源码（查证用）

```
E:\github\deepseek-harness     master 8bd073392e（0.2.2-alpha.3）
```

已配 `insteadOf` 走 ghfast 加速，`git fetch upstream` 两秒完成。
**源码的 `lib/` 是旧构建**（0.2.2 新增的 `typert.host.js` 不存在），直接 `node apps/cli/lib/bin.js`
会报 22 个 entry 激活失败。查证用源码，运行用桌面端 runtime，别混。

## 3. 已验证的事实（附验证方法）

### 3.1 单进程可服务多项目（源码结论，未实机）

- workspace = `{id: UUID, path: realpath 绝对目录, title, sessionIds, ...}`，
  `packages/workspace/workspace/src/spec.ts:24-84`
- 会话创建时 `cwd = workspace.path ?? request.cwd ?? defaultCwd` 并写进**不可变 header**，
  `packages/api/session-controller/src/commands.ts:116`
- 工具执行不看进程 cwd，取该会话的 workingDirectory，
  `packages/fs/tool-fs/src/session-cwd.ts:21-24`、`packages/shell/tool-bash/src/index.ts:488`
- 会话日志按 cwd 分目录：`sessions/--<cwd>--/<id>/`，
  `packages/session/session-persistence-jsonl/src/format.ts:254-268`

→ 一个进程服务 N 个项目成立。官方桌面端本身就是这么用的。

### 3.2 自定义 profile 能起服务（已实测）

> 下面命令里的 `dsh-ide` 是**历史 profile 名**，现默认已改为 `idea`（见 3.8）；此处保留当时的实测记录。

```bash
# 手工建 ~/.dsh/profiles/dsh-ide（package.json / cordis.patch.yml / pnpm-workspace.yaml）
D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd --profile dsh-ide --host 127.0.0.1 --port 45232 --no-open
# → dsh web: http://127.0.0.1:45232/?token=wc_...
```

**坑**：不能带 `web` 子命令。

```
❌ dsh --profile dsh-ide web --host ...   → too many arguments. Expected 0 arguments but got 1: web
✅ dsh --profile dsh-ide --host ... --port ... --no-open
```

### 3.3 认证是 token 换 cookie（已实测）

```bash
curl -D - -c cookies.txt "http://127.0.0.1:45232/?token=<launchToken>"
# HTTP/1.1 303 See Other
# set-cookie: dsh-auth-<authority>=v1.xxx; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict
```

后续请求带该 cookie。**不是 Bearer**（带 Bearer 返回 `unauthorized`）。
实现见 `packages/client/connection/src/browser-auth.ts:244-330`。

### 3.4 0.2.x 的 `/api` 是 Typert RPC（已实测）

```
POST /api/workspace.list  →  "not found"
```

0.1.x 的 `{"type":"client-request","rpcId":...}` 格式已废弃；现在是
`connection.rpc.call('/api', endpoint, payload)`（`packages/api/gateway/src/index.ts:242-264`），
底层是 connection 载体 + `createSharedFetchHandler('/api')`。

→ **workspace 注册不要在 Kotlin 里硬啃 RPC**，交给 JCEF 页面的客户端补丁（页面内已有完整 client）。

### 3.5 共享 home 的安全性（源码结论）

| 资源 | 保护 | 结论 |
|---|---|---|
| `sessions/` JSONL | 跨进程 lease（flock），append+fsync，第二写者抛 `SessionAlreadyOwnedError` | 安全 |
| `.credentials.yaml` | `<file>.lock` + 原子替换 | 安全 |
| `profiles/*/package.json`、插件安装 | `package.json.lock` | 安全 |
| **`storages/workspace.json`** | **whole-unit 整文件重写，last-write-wins** | ⚠️ 与桌面端并发可能丢更新 |
| `session-query-sqlite` | 单进程独占（当前 `:memory:` + `openAt: never`） | 无冲突 |

→ 所以策略是「检测桌面端 + 提示」，不阻塞（`DshServerService.isDesktopRunning()`）。

### 3.6 UI 没有深链参数（源码结论）

客户端只读 `?token=` / `?interactions=`（`packages/client/connection/src/browser-auth.ts:17,246,266`）。
初始工作区靠：localStorage 当前 sessionId → 其 workspace → `recentWorkspace()`（按会话 `updatedAt`），
`packages/client/ui-workspace/src/client/navigation.ts:465-495,577-596`。

→ 单进程多项目时 UI 会落到「最近聊过的项目」。**必须自己补 `ideWorkspace`**。

### 3.7 客户端补丁：URL 走 fragment（已实测）
> 0.2.0 起该补丁（`dsh-ide-client`）已并入 `dsh-plugin-jetbrains` 的 Client 半边；本节保留当时的设计记录。

官方没有深链，初始工作区由 localStorage 当前会话 → 最近项目决定。补丁 `dsh-ide-client`
由插件写入 profile 的 `node_modules/`，并通过独立的 `dsh-ide-client.patch.yml` 覆盖层挂载
（启动命令加 `--patch <该文件>`）。

**为什么不用 profile 自己的 `cordis.patch.yml`（0.1.1 修复）**：DSH 的设置编辑器（主题、语言、
欢迎语等）把新条目追加到 YAML 序列末尾，追加位置落在文件尾注释**之前**。早期版本把
`# >>> dsh-ide-client` 标记段放在文件末尾，新写入的 `ui-theme` 等条目会掉进标记段，
下次启动被整段删除——表现为「改完主题，重启回到默认」。现在插件完全不写该文件，
旧标记段只做一次性清理，且只删插件生成的行，段内其它设置行原样保留。

**关键坑**：带 `?token=` 首次打开时，DSH 返回 `303 Location: ./` 换 cookie，query 整个被丢弃，
`?ideWorkspace=` 根本到不了页面。所以改成 fragment：

```
http://127.0.0.1:45231/?token=...#ideWorkspace=<URL 编码的绝对路径>
```

RFC 7231 规定 3xx 的 Location 不含 fragment 时浏览器继承原 fragment，实测 Chromium 成立；
DSH 客户端没有 hash 路由，不冲突。`client.js` 优先读 hash、回退 query，插件保留参数不清理
（刷新仍落回 IDE 项目；重复执行安全：create 幂等、空会话复用）。

行为：Workspace 快照就绪 → `workspaces.create({path})`（Host 按 realpath 幂等）→
若 localStorage 当前会话已属于该项目则沿用官方恢复，否则 `uiWorkspace.openWorkspace()`
（内部 `beginNavigation()` 抢占启动恢复，复用/创建一个空会话）。

**侧栏只显示当前项目（0.1.2）**：DSH 与桌面端共享 `~/.dsh`，侧栏「工作区」默认列出所有 workspace。
补丁在 `workspaces.create` 之后启动 `MutationObserver`：给非当前 workspace 的 `groupSection`
打 `data-dsh-ide-hide-workspace` 属性，并注入 `display:none!important` 样式；React 重建节点后自动重打。
纯前端展示层，不改 Host 数据、不影响桌面端。限制：只在「按工作区分组」（默认）模式下隐藏整组；
切到 flat 分组时其他项目的会话仍会出现，Workspace 选择器（加号菜单）也不受影响。

实测证据（临时 `DSH_HOME` + 桌面端 runtime 0.2.1-rc.5，未触碰真实 `~/.dsh`）：

- 无 cookie 首开直接落到目标项目，303 后 URL 保留 fragment，无需刷新；
- 双项目（含空格/中文）切换正确；刷新不产生重复 workspace/会话；
- 无参打开零副作用、零 `[dsh-ide]` 日志。

实现文件：`DshClientPatch.kt`（安装/幂等维护 patch）、`DshWebUrl.kt`（fragment URL）、
`src/main/resources/dsh-ide-client/`（`package.json` / `index.js` / `client.js`）。

### 3.8 迭代 2：设置页 / 命令解析 / 并发提示 / profile 改名

**命令解析（`DshCommandResolver`）**，顺序固定：

1. **显式配置**：非空白且不是 `dsh` / `dsh.exe` / `dsh.cmd` 时按用户输入解析。带路径分隔符的按路径处理：
   绝对路径要求文件存在；相对路径按 `user.home` 解析（与 `ProcessBuilder.directory` 一致）。
2. **PATH**：Windows 按 `dsh.cmd` → `dsh.exe` → `dsh.bat` → `dsh` 顺序（与 PATHEXT 习惯一致），
   非 Windows 只找 `dsh` 且要求可执行位。
3. **桌面端已知 runtime**：`D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd`，文件真实存在才算命中。
4. **`Missing`**：message 列出已查找路径并提示 Settings，服务转 `FAILED`。

非法路径字符（`| : ? * < >`、NUL）在每处路径拼接都包 `runCatching`，按 `Missing` 处理，不抛 `InvalidPathException`；
PATH 里的坏条目只跳过、继续查找，最终找不到才返回 `Missing`。`DshServerService` 外层再 `runCatching`，
未预期异常转 `FAILED`，不会卡在 `STARTING`。

**设置页**：`Settings → Tools → DSH`（`applicationConfigurable`，id `dev.doyzheng.dsh.settings`，父级 `tools`）。
字段：dsh 命令 / 监听地址 / 端口（1..65535）/ profile / 打开项目自动启动 / 桌面端运行时仍启动并提示。
端口非法在 `apply()` 抛 `ConfigurationException`，IDE 拦下保存并停在设置页。设置是应用级（全 IDE 一份），
**变更对下次启动生效**，重启路径按状态分三种：

1. **`FAILED`（主路径）**：点工具窗口状态卡下方的「重试」按钮，立即重新调用 `startAsync()`；
2. **`STOPPED` / 工具窗口 content 重建（重开项目或 IDE）**：`DshPanel.onShown()` 只在
   `DshToolWindowFactory.createToolWindowContent` 创建 content 时调用，会重新启动；普通的显示/隐藏不保证触发；
3. **`RUNNING`**：当前没有运行中重启入口，改设置后需重启 IDE（或重启 dsh 服务）才生效。

`autoStart` 的语义边界：它只控制项目打开时**自动展示工具窗口**，不控制服务启停。服务由工具窗口 content
创建时的 `DshPanel.onShown()` 触发（`STOPPED`/`FAILED` 才启动），普通显示/隐藏不重复触发；实机待验证。

**并发提示**：`DshNotifier` 使用通知组 `DSH`（`displayType="BALLOON"`），`AtomicBoolean` 保证每个 IDE
进程只弹一次；文案为「桌面端正在运行，同时使用可能让工作区列表相互覆盖；会话与凭据不受影响。」
受 `warnWhenDesktopRunning` 控制，只提示、不阻塞启动。

**profile 改名 `dsh-ide` → `idea`**：默认值改为 `idea`；`DshSettings.loadState` 把历史值 `dsh-ide`
自动迁移为 `idea`。真实目录 `C:\Users\ZDY\.dsh\profiles\idea` 已迁移（含 model-sync、`dsh-plugin`、
`dsh-ide-client`），`package.json` 的 `name` 为 `dsh-profile-idea`；`cordis.patch.yml` 不再被插件改写，
`dsh-ide-client` 通过 `dsh-ide-client.patch.yml` 覆盖层挂载，`locale` / `ui-settings-general` 等用户设置留在原文件。

**图标**：插件列表图标 `META-INF/pluginIcon.svg`（40×40，官方资源）；工具窗口图标
`icons/dsh-toolwindow.svg`（16×16），`plugin.xml` 通过 `DshIcons.ToolWindow` 引用。
视觉与暗色主题表现留待 `runIde` 人工确认。

## 4. 未验证 / 风险

| 项 | 说明 |
|---|---|
| `runIde` 实机 | 从未在真实 IDE 里加载过；JCEF 对 303 + fragment 的继承行为未实机验证（T4 门） |
| 多窗口共享 localStorage | 同 origin 下 `dsh.sessions.current` 是全局的；每个窗口加载时按 fragment 纠正，刷新前不保证各窗口独立 |
| 端口固定与 localStorage | 端口被占用向上探测会改变 origin → localStorage 丢。已写回设置，但未验证效果 |
| `isDesktopRunning()` | 用 `ProcessHandle` 匹配 "DeepSeek Harness"，未实机确认能否命中；调用处已用 `runCatching` 兜底，异常视为「桌面端未运行」 |
| 桌面端 runtime 兜底路径硬编码 | 解析器最后一级是 Windows 硬编码路径 `D:\apps\dsh\resources\runtime\cli\bin\dsh.cmd`；非 Windows 或非本机安装会落到 `Missing`，需在 Settings 指定命令或把 dsh 加入 PATH |
| 桌面端并发写 workspace.json | 理论风险，未复现 |
| 桌面端进程退出检测 | `process.onExit()` 在 IDE 生命周期内的行为未验证 |
| 旧标记段清理 | 首次升级时若 `cordis.patch.yml` 含旧标记段，会把该文件行尾统一为 LF；只删插件生成的三行 + 两行标记，段内的用户设置行保留 |
| DSH 设置写入位置 | 主题/语言/欢迎语由 DSH 写进 profile 的 `cordis.patch.yml`；插件不再改写该文件。0.1.0 及更早版本会把段内新写入的设置删掉（主题回默认），0.1.1 已修 |
| 旧 IDE 重建 `dsh-ide` + 端口冲突 | 运行参考插件 `deepseek-harness-for-ide` 的旧 IDE 会自动重建一个**空的** `~/.dsh/profiles/dsh-ide` 并占用 45231；本插件默认端口也是 45231，同开时向上探测到 45232，origin 变化会丢 localStorage。T4 实机前建议关闭旧 IDE / 参考插件；长期共存则后续迭代改默认端口 |
| pnpm 元数据旧名 | `idea/node_modules/.modules.yaml` 等 pnpm 元数据仍含旧 profile 名，不影响运行 |
| `SecurityException` 理论缺口 | `Files.isRegularFile` 在极端环境可能抛 `SecurityException`，该调用未包 `runCatching` |
| 失败状态 detail 可能含 "null" | 解析异常/启动失败时 `DshServerService` 的 FAILED `detail = "...: ${error.message}"`，`error.message` 为 null 时会带字面量 "null"，由 `DshPanel` 状态文本展示；`DshNotifier` 本身不设 detail |

## 5. 下一步（按优先级）

### P0 ✅ 客户端补丁 `dsh-ide-client`（已完成，待实机）

最终做法（与最初设想不同，已由实测修正）：

1. 资源里放真实 npm 包 `src/main/resources/dsh-ide-client/`（`package.json` + `index.js` + `client.js`）。
2. 启动时**拷贝**到 `<profile>/node_modules/dsh-ide-client`（不用 junction，避免 Windows 链接删除风险）。
3. 写 `<profile>/dsh-ide-client.patch.yml`（`insert` 行），启动命令通过 `--patch` 挂载；不再改写
   `cordis.patch.yml`，避免与 DSH 设置编辑器的写入互相覆盖。旧版本留下的标记段只做一次性清理。
4. `client.js` 读 fragment（回退 query）→ `workspaces.create`（幂等）→ `uiWorkspace.openWorkspace`。
5. JCEF URL 由 `DshWebUrl` 生成，`ideWorkspace` 放 fragment，规避 token 换 cookie 的 303 丢 query。

验收证据（P0 阶段）：12 个单测（`DshClientPatchTest` / `DshWebUrlTest`）、`buildPlugin`、真实 runtime 浏览器冒烟。
剩余：`runIde` 实机（T4）。

### P1 ✅ 运行时解析 + 设置页（已完成）

- `DshCommandResolver`：显式配置 → PATH → 桌面端 runtime → `Missing`（详见 3.8）
- `DshSettingsConfigurable`：命令、监听地址、端口、profile、自动启动、并发提示开关
- 未做：启动前 Node 版本 / home 可写预检；DSH 自身启动失败会以 `FAILED` + detail 呈现

### ~~P2 workspace 注册~~（已取消）

客户端补丁已覆盖 workspace 注册与选中，不再在 Kotlin 侧实现 Typert RPC。

### P3 ✅ 双向桥：W→IDE 打开文件（0.1.5 服务覆盖）+ IDE→对话发送（0.1.6）

**W→IDE（打开文件 / 定位行）**：`DshPanel` 用 `JBCefJSQuery` 注入 `window.__dshIdeOpenFile(payload)`，
页面每次 `onLoadEnd` 重新注入。`dsh-ide-client` 不再劫持 DOM 点击，而是**覆盖 DSH 的
`ctx.sidebarRight.openResource(address, options)`**——Web UI 里所有「打开文件」最终都走这个入口
（@文件 chip、正文 Markdown 链接、交付卡片、改动文件卡片、内置文件树）：

- `dsh-resource://file/session/<id>/<path>` / `.../absolute/<path>` → 解析路径，相对路径按该会话 cwd 解析，
  通过桥交给 IDEA；`options.params.line` 一并传过去（1-based，Kotlin 转 0-based）
- `dsh-resource://changes-review/session/<id>/<seq>/<turn>` + `params.index` → 先 `fetch('api/changes.summary?...')`
  取该轮摘要，再打开第 index 个文件；取不到就回退官方 review tab
- `openResourceIn`（右键菜单「在侧边栏/浮窗打开」、文件树打开动作）同样覆盖
- 桥不存在（普通浏览器）或覆盖失败 → 原方法照常执行，行为不变

**IDE→对话（0.1.6）**：三个右键入口（`ProjectViewPopupMenu` / `EditorTabPopupMenu` / `EditorPopupMenu`）
把文件/目录引用、编辑器选区（只发路径与起止行，不发原文）发进 DSH 输入框：

```
SendFileToDshAction / SendSelectionToDshAction        （action/）
  → DshIdeService（项目级）                            面板未挂载时先排队，toolWindow.show 回调里转交
  → DshPanel.pushToWebview(json)                       页面未加载完成时再排队，onLoadEnd 冲刷
  → window.__dshIdePush(json)                          Kotlin 注入的入口；页面侧没就绪先进 __dshIdePendingPushes
  → __dshIdeInsertRefs（client.js）                    workspace 相对化 → ReferenceInsert
  → conversation.input.for(actx).insertReference(...)  官方 @ 选择器同款结构化 chip；老版本回退纯文本 @路径
```

- 消息契约（`DshComposerMessage`，有单测）：`{op:"fileRefs", refs:[{path, kind, startLine?, endLine?}]}`
- 选区后缀拼成 `@路径 (L12-L30)`；带空格路径用 `@"..."` 引用语法（`formatFileMention` 同款）；
  workspace 外的文件保留绝对路径
- **插入点固定为草稿末尾**：IDE 右键发送时输入框必然未聚焦，`captureInsertion()` 读到的是不可靠的
  陈旧选区（实测落在文中间）；官方批量接口没暴露，逐个 `insertReference` + 显式 span 等价达成
- **坐标注意（踩坑）**：`TokenSpan` 用 detect 投影——每个 chip 只占 1 个占位字符 U+FFFC；
  `state.draft` 是 clipboard 投影（chip 展开成 `@路径`）。追加位置按 occurrences/claim 把 clipboard
  长度折算回 detect 长度，否则第二个 chip 起全部静默失败
- 诊断钩子：`window.__dshIdeComposerState()`（draft/rev/span）、`window.__dshIdeComposerReady`

未做：IDE 原生 Diff（需要 Host 侧提供对比数据，另开迭代）。

### P4 ✅ 桌面端并发提示（已完成）

`DshNotifier` 在 `isDesktopRunning()` 命中时弹一次性通知（通知组 `DSH`）：
「桌面端正在运行，同时使用可能让工作区列表相互覆盖；会话与凭据不受影响」。
只提示、不阻塞，受 `warnWhenDesktopRunning` 控制。

### 发布门

`runIde` 实机验收在发布前必须通过；版本已升 `0.1.6`，`runIde` 通过后即可发布。

## 6. 踩过的坑

| 坑 | 处理 |
|---|---|
| `--profile dsh-ide web` 报 `too many arguments`（当时的 profile 名，现为 `idea`） | `web` 是 `--profile web` 语法糖，自定义 profile 不能带 |
| 自定义 profile 名没有模板，目录不存在直接抛错 | 插件自己建目录（`DshProfileBootstrap`），内容等价 `initProfile()` |
| 源码 `lib/` 是旧的，`node apps/cli/lib/bin.js` 22 个 entry 失败 | 查证用源码，运行用桌面端 runtime |
| `Bearer token` 调 API 返回 `unauthorized` | 必须先 `GET /?token=` 换 cookie |
| `POST /api/workspace.list` 返回 `not found` | 0.2.x 已是 Typert RPC，不是 JSON-RPC |
| 桌面端 dsh 在 `app.asar` 里，找不到独立 `bin.js` | 用 `D:\apps\...\dsh.cmd`（RunAsNode 包装） |
| 输入框 `captureInsertion()` 在未聚焦时返回陈旧选区（落在文中间） | 插入点固定为草稿末尾，span 显式构造 |
| `TokenSpan` 是 detect 坐标（chip 占 1 字符），`state.draft` 是 clipboard 坐标 | 按 `occurrences`/`claim` 把 clipboard 长度折算回 detect；不折算时第 2 个 chip 起全部静默失败 |
| Kotlin→JS 推送在页面侧没就绪时会丢 | Kotlin 注入的 `__dshIdePush` 先排进 `__dshIdePendingPushes`，client.js apply 时冲刷 |

## 7. 文件地图

```
E:\gitee\doyzheng\dsh-ide
├─ build.gradle.kts                    IPGP 2.2.1 + Kotlin 2.1，IC 2025.1（插件名 dsh-idea-plugin）
├─ gradle.properties                   platformVersion / platformType
├─ .agents/docs/architecture.md                设计依据（源码路径级证据）
├─ HANDOFF.md                          本文件
├─ dsh-plugin-jetbrains/                ★ 真正的 DSH 插件（纯 JS Cordis 包，包名 dsh-plugin-jetbrains）
│  ├─ package.json                     dsh.bundle.patch + dsh.client
│  ├─ cordis.patch.yml                 插入 Host 行
│  ├─ index.js                         Host：JetBrains 检测 / 安装卸载 / profile / HTTP 路由
│  ├─ client.js                        Client：settings.section「DSH IDE 集成」
│  ├─ locale/{zh,en}.json、icon.svg    显示元数据
│  ├─ assets/dsh-idea-plugin.zip(+.version)   IDEA 插件构建产物
│  └─ scripts/{sync-ide-artifact.ps1,selftest.mjs}
└─ （仓库根即 IDEA 插件 Kotlin 工程：build.gradle.kts / gradlew / gradle / src / .intellijPlatform）
   └─ src
   ├─ main
   │  ├─ kotlin/dev/doyzheng/dsh/ide
   │  │  ├─ DshSettings.kt             应用级设置（端口 45231 / profile idea）
   │  │  ├─ DshCommandResolver.kt      命令解析：配置 → PATH → 桌面端 runtime → Missing
   │  │  ├─ DshProfileBootstrap.kt     幂等创建 profile，不覆盖已有内容
   │  │  ├─ DshWebUrl.kt               ideWorkspace 的 fragment URL 构造
   │  │  ├─ DshStartupActivity.kt      项目打开自动展示工具窗口（受 autoStart 控制）
   │  │  ├─ DshServerService.kt        应用级单例：进程、端口探测、退出监控
   │  │  ├─ DshNotifier.kt             桌面端并发提示（通知组 DSH，每进程一次）
   │  │  ├─ DshComposerMessage.kt      IDE→对话消息 JSON（op=fileRefs，纯函数有单测）
   │  │  ├─ DshIdeService.kt           项目级：右键动作 → 面板路由，面板未挂载时排队
   │  │  ├─ action
   │  │  │  ├─ SendFileToDshAction.kt        项目树/编辑器 Tab/编辑器右键 → 文件引用
   │  │  │  └─ SendSelectionToDshAction.kt   编辑器选区右键 → 路径 + 起止行
   │  │  ├─ settings
   │  │  │  └─ DshSettingsConfigurable.kt  Settings → Tools → DSH
   │  │  ├─ icons
   │  │  │  └─ DshIcons.kt             工具窗口图标（plugin.xml 引用）
   │  │  └─ ui
   │  │     ├─ DshToolWindowFactory.kt
   │  │     └─ DshPanel.kt             JCEF + ideWorkspace + 双/三向桥（打开文件 / 推送消息 / 重启宿主 Web）
   │  └─ resources
   │     ├─ icons/dsh-toolwindow.svg   工具窗口 16×16
   │     └─ META-INF
   │        ├─ pluginIcon.svg          插件列表 40×40
   │        ├─ plugin.xml              dev.doyzheng.dsh-ide（name=dsh-idea-plugin，含 actions 注册）
   │        └─ dsh-ide-jcef.xml        build 262 的 JCEF 独立模块
   └─ test/kotlin/dev/doyzheng/dsh/ide
      ├─ DshCommandResolverTest.kt / DshComposerMessageTest.kt
      └─ DshSettingsTest.kt / DshWebUrlTest.kt
```

## 8. 提交前检查

已通过：

- [x] `./gradlew test`：31/31（`DshCommandResolverTest` 16 + `DshComposerMessageTest` 6 + `DshSettingsTest` 4 + `DshWebUrlTest` 5）
- [x] `./gradlew buildPlugin`：约 78 KB zip（移除内置客户端包后变小）；内层 jar 含 `META-INF/pluginIcon.svg`、
      `icons/dsh-toolwindow.svg`、`DshIcons.class`、`DshSettingsConfigurable.class`、
      `DshNotifier.class`、`DshCommandResolver.class`、`DshComposerMessage.class`、`DshIdeService.class`、
      `action/SendFileToDshAction.class`、`action/SendSelectionToDshAction.class`
- [x] 启动命令不再带 `--patch`；profile 不再写 `dsh-ide-client.patch.yml`（浏览器补丁已并入 `dsh-plugin-jetbrains`）
- [x] 真实 runtime 浏览器冒烟：无 cookie 首开、双项目（空格/中文）、刷新幂等、query 回退、无参静默
- [x] 实机（真实 IDEA + JCEF，0.1.5/0.1.6）：文件树/对话文件引用点击在 IDEA 打开并定位行；
  项目树右键、编辑器 Tab 右键、编辑器选区右键三个入口都把引用插入 DSH 输入框
  （chip 渲染、`@路径 (L12-L30)` 后缀、带空格路径引号语法、workspace 外绝对路径、单空格分隔均已验证）

待人工（`runIde`，T4）：

- [ ] 首次启动（无 cookie）工具窗口直接落到当前项目，无需刷新
- [ ] 两个项目窗口各落各自项目，互不串台
- [ ] 在 Settings → Tools → DSH 关闭 `autoStart` 后不自动展示窗口；端口填 0 / 70000 时保存被拒绝
- [ ] 构造启动失败（如 dsh 命令填不存在路径）：状态卡出现「重试」；改正命令后点「重试」能启动（不重启 IDE）
- [ ] 与桌面端同时运行时，会话列表互相可见（验证共享 home），并发提示只弹一次
- [ ] `idea.log` 中 `[dsh]` 日志显示固定端口，无 JCEF 报错
- [ ] 图标（插件列表 40×40、工具窗口 16×16）视觉正常，暗色主题下可辨识
- [ ] 侧栏「工作区」只显示当前项目；切到 flat 分组时不保证
- [ ] 修改 DSH 主题色/语言后重启 IDE，设置保持（`cordis.patch.yml` 里的 `ui-theme` 等条目不被删）
- [ ] 发送引用时若 composer 正在被用户打字（CAS 拒绝），行为可接受（当前实现：跳过该次插入，不覆盖用户输入）
