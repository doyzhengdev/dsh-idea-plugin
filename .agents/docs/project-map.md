# DSH for JetBrains — 项目地图

> 本文是**结构导航 + 代码级核查**：从「一句话定位」到「改哪儿」，再到「哪儿有坑」。
> 决策依据见 [architecture.md](architecture.md)，进度与踩坑见 [HANDOFF.md](../../HANDOFF.md)，协作规则见 [AGENTS.md](../../AGENTS.md)。
> 更新时间：2026-11-05 · 对应版本 **`0.2.1`（管理功能已迁到 IDEA 插件 Settings）** · 基于源码通读 + 构建产物核对。

> **本次结构变更（以 IDEA 插件为主）**：管理功能（检测 JetBrains / 安装卸载本插件 / profile 管理 / 重启服务）
> 全部在 IDEA 插件 `Settings → Tools → DSH`（Kotlin `settings/` + `jetbrains/`）；DSH 插件 `dsh-plugin-jetbrains`
> 只保留 IDE 桥（`client.js`）+ 运行期 `ensureIdeBundle`（`index.js`）。下文 §2 的目录地图为 0.1.6 历史，
> 当前以仓库根目录（Gradle 工程根 + `dsh-plugin-jetbrains/` 子目录）为准。

---

## 0. 一句话

给 JetBrains IDE 做的 DSH 插件：**全 IDE 一个 `dsh` 进程、共享桌面端同一份 `~/.dsh`、项目靠 URL fragment 参数区分**，
工具窗口内嵌官方 Web UI（JCEF），不自研前端；再通过 JCEF 桥实现**双向互通**（DSH 点文件→IDE 打开；IDE 右键→发进 DSH 输入框）。

与 `deepseek-harness-for-ide`（一项目一进程 + 每项目隔离 home）取舍**相反**：

| 维度 | 本插件 | 参考插件 |
|---|---|---|
| 进程数 | 1 个服务所有项目窗口 | N 个项目 N 个进程 |
| DSH home | 共享 `~/.dsh` | 每项目隔离 |
| 会话可见性 | IDE 与桌面端互通 | 桌面端会话在 IDE 里看不到 |
| 项目区分 | URL `#ideWorkspace=<绝对路径>` | 进程隔离天然区分 |
| 主要代价 | workspace 列表 `last-write-wins` 风险 | N 份运行时、N 个 Node 进程 |

---

## 1. 技术栈、构建与规模

| 项 | 值 | 出处 |
|---|---|---|
| 语言 | Kotlin 2.1.0（JVM target 21） | `build.gradle.kts:5,41-45` |
| 平台插件 | IntelliJ Platform Gradle Plugin `2.2.1` | `build.gradle.kts:6` |
| 目标平台 | IC `2025.1`，`since-build=243`、`until-build=262.*` | `gradle.properties:8-9` |
| Gradle | 8.13（wrapper） | `gradle/wrapper/gradle-wrapper.properties:3` |
| 测试框架 | JUnit 4.13.2 | `build.gradle.kts:34` |
| 构建 JDK | `D:\apps\jdk\jdk-21.0.2`（**默认 `JAVA_HOME` 是 JDK 8，必须显式指定**） | `AGENTS.md` |
| 版本号 | `0.1.6`，须同时改 `build.gradle.kts:10` 与 `plugin.xml:5` | skill `deploy-dsh-ide-plugin` §1 |
| 产物 | `build/distributions/dsh-ide-0.1.6.zip`（93,693 B），内层 `dsh-ide/lib/dsh-ide-0.1.6.jar`（97,780 B） | 已核对 |

**规模**（逐文件实测）：**16 个 Kotlin 主源文件 / 1,659 行** + **5 个测试文件 / 839 行** + **内置 JS 客户端 675 行**（`client.js` 668 + `index.js` 7）+ 2 篇文档 + 2 个项目级技能。

```bash
export JAVA_HOME="D:\\apps\\jdk\\jdk-21.0.2"
./gradlew test --offline            # 单元测试
./gradlew buildPlugin --offline     # → build/distributions/dsh-idea-plugin-<version>.zip
```

---

## 2. 目录地图（当前 0.1.6）

```
E:\gitee\doyzheng\dsh-ide
├─ AGENTS.md                     项目规则：能自己做的绝不甩给用户
├─ HANDOFF.md                    交接文档（421 行；状态表部分滞后，见 §12-D）
├─ build.gradle.kts              构建：IPGP 2.2.1 + Kotlin 2.1 + JVM 21
├─ gradle.properties             目标平台 IC 2025.1
├─ .gitignore                    排除 build/.gradle/.idea/*.zip   ← 但仓库目前没有 .git
├─ .agents/skills/
│  ├─ deploy-dsh-ide-plugin/     打包 → 关 IDE → 替换插件 → 重启 → 验证（8 个脚本）
│  └─ codegraph-dsh-source/      查官方源码先走 codegraph 索引
├─ .agents/docs/
│  ├─ architecture.md            架构依据（源码 `packages/...:行号` 级证据，173 行）
│  └─ project-map.md             本文件
└─ src
   ├─ main
   │  ├─ kotlin/dev/doyzheng/dsh/ide
   │  │  ├─ DshServerService.kt   ★ 核心：应用级单例，进程托管/端口探测/健康检查（341 行）
   │  │  ├─ DshCommandResolver.kt dsh 命令解析：配置→PATH→桌面端 runtime→Missing（235 行）
   │  │  ├─ DshClientPatch.kt     安装内置客户端包 + 写 `--patch` 覆盖层（127 行）
   │  │  ├─ DshProfileBootstrap.kt 幂等创建 profile 目录，不覆盖已有内容（81 行）
   │  │  ├─ DshSettings.kt        设置数据类 + 历史 profile 迁移（64 行）
   │  │  ├─ DshWebUrl.kt          `#ideWorkspace=` fragment URL 构造（31 行）
   │  │  ├─ DshStartupActivity.kt 打开项目自动展示工具窗口（27 行）
   │  │  ├─ DshNotifier.kt        桌面端并发提示，每进程一次（54 行）
   │  │  ├─ DshComposerMessage.kt ★ IDE→对话消息 JSON 契约（op=fileRefs，纯函数）（54 行）
   │  │  ├─ DshIdeService.kt      ★ 项目级：右键动作→面板路由，面板未挂载时排队（90 行）
   │  │  ├─ action/
   │  │  │  ├─ SendFileToDshAction.kt      项目树/编辑器 Tab/编辑器右键→文件引用（47 行）
   │  │  │  └─ SendSelectionToDshAction.kt 编辑器选区右键→路径+起止行（61 行）
   │  │  ├─ settings/DshSettingsConfigurable.kt  Settings → Tools → DSH（104 行）
   │  │  ├─ ui/DshToolWindowFactory.kt     工具窗口工厂（23 行）
   │  │  ├─ ui/DshPanel.kt        ★ 核心：状态卡 + JCEF + 双向桥（310 行）
   │  │  └─ icons/DshIcons.kt              工具窗口图标（10 行）
   │  └─ resources
   │     ├─ dsh-ide-client/      ★ 内置 npm 包，注入 DSH 页面（client.js 668 行）
   │     │  ├─ package.json      `dsh.client.platform=web` + `exports["./client"]`
   │     │  ├─ index.js          Host 侧空插件（必须能 import，否则整行加载失败）
   │     │  └─ client.js         浏览器补丁：ideWorkspace / 侧栏过滤 / 打开文件覆盖 / 引用插入
   │     ├─ icons/dsh-toolwindow.svg   工具窗口 16×16
   │     └─ META-INF/
   │        ├─ plugin.xml        插件声明 + actions 注册（58 行）
   │        ├─ pluginIcon.svg    插件列表 40×40
   │        └─ dsh-ide-jcef.xml  build 262 JCEF 独立模块的 optional depends 占位
   └─ test/kotlin/dev/doyzheng/dsh/ide
      ├─ DshCommandResolverTest.kt  16 条：解析顺序/非法路径字符/缺失提示（435 行）
      ├─ DshClientPatchTest.kt      8 条：覆盖层/幂等/旧标记清理保留设置（200 行）
      ├─ DshComposerMessageTest.kt  6 条：fileRefs 契约（kind/行号/空白跳过）（99 行）
      ├─ DshWebUrlTest.kt           5 条：fragment 编码（61 行）
      └─ DshSettingsTest.kt         4 条：默认 profile 与历史值迁移（44 行）
```

---

## 3. 插件装配（`plugin.xml`）

| 扩展点 | 实现 | 作用 |
|---|---|---|
| `applicationService` | `DshSettings` | 应用级设置（`dsh-ide.xml`，component `DshIdeSettings`） |
| `applicationService` | `DshServerService` | 应用级单例进程宿主 |
| `postStartupActivity` | `DshStartupActivity` | 项目打开后自动展示工具窗口（受 `autoStart`） |
| `toolWindow id=DSH` | `DshToolWindowFactory` | 右侧 secondary，`doNotActivateOnStart=true` |
| `applicationConfigurable` | `DshSettingsConfigurable` | Settings → Tools → DSH（id `dev.doyzheng.dsh.settings`） |
| `notificationGroup id=DSH` | `DshNotifier` | BALLOON 气泡提示 |
| `optional depends` | `com.intellij.modules.jcef` → `dsh-ide-jcef.xml` | PyCharm 2026.2 起 JCEF 独立模块（占位保依赖可解析） |
| **`action`** | `SendSelectionToDshAction` | `EditorPopupMenu` 首位（0.1.6） |
| **`action`** | `SendFileToDshAction` | `ProjectViewPopupMenu` / `EditorTabPopupMenu` / `EditorPopupMenu` 首位（0.1.6） |

平台依赖只有 `com.intellij.modules.platform`，无 Java/Kotlin 专用依赖，可装在任何 JetBrains IDE。

**项目级服务**：`DshIdeService` 由 `@Service(Level.PROJECT)` 注册，不需要在 `plugin.xml` 声明。

---

## 4. 运行时架构

### 4.1 进程与数据

```
IDE 进程
└─ DshServerService（applicationService 单例）
   └─ cmd.exe /c dsh.cmd --profile idea
        --patch ~/.dsh/profiles/idea/dsh-ide-client.patch.yml
        --host 127.0.0.1 --port 45231 --no-open
        │  DSH_HOME 未设置 → ~/.dsh（与桌面端共享）
        ├─ ~/.dsh/.credentials.yaml        共享，<file>.lock + 原子替换        → 安全
        ├─ ~/.dsh/sessions/                共享，跨进程 lease + append fsync    → 安全
        ├─ ~/.dsh/storages/workspace.json  共享，整文件重写 last-write-wins     → ⚠️ 可能丢更新
        └─ ~/.dsh/profiles/idea/           ⚠️ 本机实为指向 desktop 的 junction（见 §12-C）

JCEF 面板（每项目一个，共享同一端口）
└─ http://127.0.0.1:45231/?token=...#ideWorkspace=<URL 编码的绝对路径>
   └─ dsh-ide-client/client.js：读 fragment → workspaces.create → uiWorkspace.openWorkspace
                                + 覆盖 sidebarRight.openResource（打开文件进 IDE）
                                + 注册 __dshIdeInsertRefs（接收 IDE 推送的引用）
```

### 4.2 关键设计取舍（每条都有源码依据，见 `architecture.md` §2）

1. **单进程多工作区成立**：workspace 是 `{id, path, title, sessionIds}` 持久实体；会话创建时把 `cwd` 写进不可变 header；工具执行取会话 workingDirectory，不看进程 cwd。
2. **必须共享 `~/.dsh`**：不设 `DSH_HOME`，凭据/会话/设置与桌面端完全互通——这是核心卖点。
3. **必须固定端口**：origin 变化会丢 localStorage（含当前会话）。端口被占向上探测 +50，并写回设置。
4. **必须自己补深链**：官方客户端只读 `?token=` / `?interactions=`，初始工作区靠 localStorage→最近项目。
5. **参数必须放 fragment**：带 token 首开返回 `303 Location: ./` 换 cookie，query 被整体丢弃；fragment 按 RFC 7231 继承（实测 Chromium 成立）。
6. **不改写 `cordis.patch.yml`**：DSH 设置编辑器把主题/语言条目追加到该文件末尾，早期用标记段维护会被下次启动整段删除（0.1.1 修复）。改独立 `dsh-ide-client.patch.yml` + `--patch` 挂载。
7. **打开文件不劫持 DOM**（0.1.5）：改为覆盖 `ctx.sidebarRight.openResource/openResourceIn`，覆盖 @chip、Markdown 链接、交付/改动卡片、内置文件树所有入口。
8. **IDE→对话只发引用不发原文**（0.1.6）：模型按行号自读文件；插入用官方 `insertReference` 结构化 chip，不用手拼文本。

---

## 5. 关键数据流

### 5.1 启动链（`DshServerService.startInternal`，lifecycle 单线程）

```
startAsync()  ← DshPanel.onShown()（STOPPED/FAILED）或「重试」按钮
 └ startInternal()
    ├ 幂等守卫：RUNNING/STARTING 直接返回
    ├ publish(STARTING)  ← 用 settings.port（可能是被占的上游值）
    ├ DshProfileBootstrap.ensure(profile)      失败 → FAILED「无法创建 profile」
    ├ DshClientPatch.install(profileDir)       失败只 warn，降级「最近项目」，不阻塞
    ├ isDesktopRunning() && warnWhen…          DshNotifier 提示一次（runCatching 兜底）
    ├ resolveFreePort(port)                    向上探测 50 个，全占则 OS 分配
    ├ DshCommandResolver.resolve(…)            Missing → FAILED 并 return（不得照样起进程）
    ├ ProcessBuilder(cmd.exe /c …).start()     cwd = user.home，stderr 合并进 stdout
    ├ awaitUrl(90s)     读 stdout 匹配 http(s)://…?token=…
    ├ awaitHealthy(45s) GET 且 instanceFollowRedirects=false，接受 200..399
    ├ publish(RUNNING, url, port)              ← 实际端口
    └ watch() → process.onExit() 非主动停止 ⇒ publish(FAILED)
```

停止/卸载：`dispose()` → `stopInternal()` → Windows `taskkill /T /F`（dsh 派生工具/pty 子进程，必须杀整棵树）。

### 5.2 项目定位链（每窗口落到自己的项目）

```
DshPanel.render(RUNNING)
 └ DshWebUrl.workspaceUrl(base, project.basePath)   → 追加 #ideWorkspace=<encoded>
    └ JBCefBrowser.loadURL(...)                      → 303 换 cookie，fragment 保留
       └ client.js apply(ctx)
          ├ readWorkspaceParam()   优先 hash，回退 query
          ├ waitUntil(workspaces.list.phase === 'ready')   ← 最长 30s
          ├ workspaces.create({path})                     ← Host 按 realpath 幂等
          ├ startWorkspaceFilter(workspaceId)             ← MutationObserver 隐藏其他分组
          ├ 当前会话已属该项目 → 沿用官方恢复；否则 uiWorkspace.openWorkspace(id)
```

参数**故意不清理**：刷新后仍落回 IDE 项目。

### 5.3 双向桥

**A. DSH → IDE（打开文件，0.1.5）**

```
用户点 @chip / Markdown 链接 / 交付卡片 / 改动卡片 / 文件树
 └ ctx.sidebarRight.openResource(address, options)   ← 被 client.js 覆盖
    ├ dsh-resource://file/session/<id>/<path> | .../absolute/<path>
    │   → parseFileAddress → 相对路径按会话 cwd 解析 → window.__dshIdeOpenFile({path,line,column})
    ├ dsh-resource://changes-review/session/<id>/<seq>/<turn> + params.index
    │   → fetch('api/changes.summary?sessionId=&seq=') 取第 index 个文件（⚠️ 见 §12-I）
    └ 非文件资源 → 调用原 openResource（官方侧栏预览不变）

Kotlin：JBCefJSQuery handler → invokeLater → openInEditor（project.basePath 解析，1-based→0-based）
```

**B. IDE → DSH（插入引用，0.1.6）**

```
SendFileToDshAction / SendSelectionToDshAction（action/，右键菜单）
 → DshIdeService（项目级）：toolWindow.show 回调里转交；面板未挂载先排队
 → DshPanel.pushToWebview(json)：pageLoaded 前再排队，onLoadEnd 冲刷
 → window.__dshIdePush(json)（Kotlin 注入）→ 页面侧没就绪则入 __dshIdePendingPushes
 → __dshIdeInsertRefs（client.js）：workspace 相对化 → ReferenceInsert 结构化 chip
 → conversation.input.for(actx).insertReference(...)（官方 @ 选择器同款；老版本回退纯文本 @路径）
```

契约：`{op:"fileRefs", refs:[{path, kind, startLine?, endLine?}]}`（`DshComposerMessage`，6 条单测）。
选区委 `@路径 (L12-L30)`；带空格路径 `@"..."`；workspace 外保留绝对路径；插入点固定草稿末尾。

---

## 6. 配置面

| 配置 | 位置 | 说明 |
|---|---|---|
| `DshIdeSettings` | `options/dsh-ide.xml`（IDE 配置目录；未改动前不存在） | 应用级 |
| 字段 | `dshCommand` / `host` / `port` / `profile` / `autoStart` / `warnWhenDesktopRunning` | 默认 `dsh` / `127.0.0.1` / `45231` / `idea` / true / true |
| 版本迁移 | `DshSettings.loadState` | 历史 profile 值 `dsh-ide` → `idea` |
| profile 目录 | `<DSH_HOME>/profiles/<profile>`，`DSH_HOME` 优先否则 `~/.dsh` | 本机 `idea`/`web` 均为 → `desktop` 的 junction |
| 客户端包 | `<profile>/node_modules/dsh-ide-client/` | 每次启动按内容比对覆盖（拷贝，不用 junction） |
| 覆盖层 | `<profile>/dsh-ide-client.patch.yml` | `--patch` 挂载，插件自动维护 |

**设置变更生效时机**（`RUNNING` 中无重启入口）：`FAILED`→点「重试」；`STOPPED`→重开窗口/项目；`RUNNING`→重启 IDE。

---

## 7. 改哪儿：变更热点索引

| 需求 | 落点 |
|---|---|
| 改默认端口/地址/profile | `DshSettings.kt:56-58`（常量）+ `:24-41`（默认值） |
| 改启动参数 | `DshServerService.kt:166-205` `buildCommand`（launcher 旗标须在应用参数前） |
| 改命令查找顺序 | `DshCommandResolver.kt:64-112` `resolve` + `:122-143` `resolveAuto` |
| 改端口探测 | `DshServerService.kt:207-224` |
| 改启动超时 | `DshServerService.kt:142`（URL 90s）/ `:151`（健康 45s） |
| 改状态卡/重试 | `DshPanel.kt:109-175` |
| 改页面 URL 参数 | `DshWebUrl.kt` + `client.js:40-50`（两侧同步） |
| 改打开文件行为（DSH→IDE） | `client.js:87-275`（`installSidebarOpenOverride`）+ `DshPanel.kt:258-292` |
| 改引用插入（IDE→DSH） | `client.js:277-518` + `DshPanel.kt:226-256` + `DshComposerMessage.kt` |
| 加/改右键菜单入口 | `plugin.xml:44-57` + `action/` |
| 改设置页 | `DshSettingsConfigurable.kt:34-52,64-80` |
| 升级 profile 客户端包 | `src/main/resources/dsh-ide-client/` + `DshClientPatch.kt:29` 文件清单 |

---

## 8. 项目级技能（`.agents/skills/`）

| 技能 | 何时用 | 关键约束 |
|---|---|---|
| `deploy-dsh-ide-plugin` | 改完 Kotlin 要在真实 IDE 看效果 | 打包→**优雅关 IDE**→**整目录删除再解压**→重启→查日志验证；不跑 `runIde`（本机直接替换已装插件更真实更快） |
| `codegraph-dsh-source` | 查 DSH 官方源码（`E:\github\deepseek-harness`） | 先 `codegraph` 再 grep；索引约 733MB；12 个 `client.ts` 解析失败需回退 `rg` |

---

## 9. 当前状态（2026-11-05 实测）

| 项 | 状态 | 证据 |
|---|---|---|
| 单测 | ✅ **39/39** | 本次实跑 `./gradlew test` BUILD SUCCESSFUL；XML：8+16+6+4+5 |
| 打包 | ✅ `dsh-ide-0.1.6.zip`（93,693 B） | `build/distributions/` |
| 已装插件 | ✅ 0.1.6（jar 97,780 B，plugin.xml `<version>0.1.6</version>`） | `%APPDATA%\...\plugins\dsh-ide\lib\` |
| IDE 加载 | ✅ `DSH for JetBrains (0.1.6)` | `idea.log` 03:06 Loaded custom plugins |
| dsh 进程 | ✅ 监听 `127.0.0.1:45231` | `idea.log` 03:06 启动命令含 `--profile idea --patch …`；端口 owner pid 34228 |
| W→IDE 桥 | ✅ 实机有大量记录 | `idea.log` 多次 `DSH 打开文件: …:line:col` |
| IDE→对话桥 | ✅ HANDOFF §8 记录实机验证（0.1.6） | 三个右键入口 → chip / `(L12-L30)` / 引号语法 / 绝对路径 |
| architecture §5 | ✅ 已同步到 0.1.6 | 文档 03:09 更新 |

---

## 10. 风险与待办

### 10.1 代码/运行时风险

| 风险 | 位置 | 说明 |
|---|---|---|
| FAILED→重试→RUNNING 面板可能脱位 | `DshPanel.kt:109-175` | 见 §12-A（中） |
| workspace.json `last-write-wins` | 数据层 | 与桌面端同时运行可能丢更新，只提示不阻塞 |
| 多窗口共享 localStorage | `client.js` | 同 origin 下 `dsh.sessions.current` 全局；靠每次加载按 fragment 纠正 |
| 端口漂移改变 origin | `DshServerService.kt:115-121` | 已写回设置，丢 localStorage 的既有影响未量化 |
| 桌面端检测不可靠 | `DshServerService.kt:337-339` | 进程名匹配 `"DeepSeek Harness"`；已 `runCatching` 兜底 |
| 硬编码 runtime 路径 | `DshCommandResolver.kt:44` | 非 Windows/非本机 → `Missing` |
| 改动摘要 GET 路由未验证 | `client.js:178-193` | 见 §12-I（中，待验证） |
| 无当前会话时推送被丢弃 | `client.js:433-437` | 见 §12-J（低） |
| 客户端补丁依赖上游 bundle 结构 | `client.js` | 上游改前端会破坏补丁；建议构建期断言 + 锁版本 |

### 10.2 工程层

| 问题 | 说明 |
|---|---|
| **仓库没有 `.git`** | 有 `.gitignore` 但 `Test-Path .git` = False，向上无父仓库；无版本历史/无法回滚。建议 `git init` + 首提交 |
| 根目录空 `META-INF/`（0 子项） | 疑似早期误建，可删 |
| `build/libs` 堆积 24 个旧 jar、`build/distributions` 8 个 zip | 可 `./gradlew clean` 清理 |
| 文档漂移 | 见 §12-D（HANDOFF 状态表、deploy skill 版本/profile） |

---

## 11. 文档入口

| 想知道 | 看 |
|---|---|
| 项目是什么、怎么跑、进度、踩坑 | [HANDOFF.md](../../HANDOFF.md) |
| 为什么这样设计（源码行号级依据） | [architecture.md](architecture.md) |
| 代码结构、数据流、改哪儿、代码级问题 | 本文件 |
| 协作规则 | [AGENTS.md](../../AGENTS.md) |
| 怎么部署到 IDE 并验证 | `.agents/skills/deploy-dsh-ide-plugin/SKILL.md` |
| 怎么查 DSH 官方源码 | `.agents/skills/codegraph-dsh-source/SKILL.md` |

---

## 12. 代码级审查发现（静态分析，按严重度）

> 说明：本轮为**通读源码 + 实际运行状态核对**得出的静态结论；标注「待验证 / 未实机复现」的条目未做动态复现。

### A.【中】`DshPanel` 从 FAILED 恢复后浏览器组件可能脱位（疑似空白）
`DshPanel.kt:109-175`：`render(RUNNING)` 只在 `browser == null` 或 `loadedUrl != target` 时才把 `browser.component`
放回 `root` 或重新 `loadURL`。但 `FAILED` 时 `showStatusPanel()` 会把 `statusPanel` 加到 `BorderLayout.CENTER`
（与浏览器同一布局槽）。若之后「重试」成功且端口未变（`target` 相同），`existing != null` 且 `loadedUrl == target`，
即**既不重新加组件、也不 reload** → 面板可能空白/错位。
触发链：dsh 进程崩溃(FAILED) → 用户点「重试」→ 同端口 RUNNING。
建议：RUNNING 分支无条件确保 `browser.component.parent === root`（先 remove 再 add），或 `showStatusPanel()`
在 `browser != null` 时不抢占 CENTER（改为覆盖层）。**未实机复现**，建议按此链路做一次实机回归。

### B.【低】`showStatusPanel()` 存在空 `if` 块（死代码）
`DshPanel.kt:166-169`：`if (browser != null) { /* 仅注释，无动作 */ }`，移除可读性更好。

### C.【中】`idea` profile 实为指向 `desktop` 的 junction，与「profile 独立」设计相悖，且当前两端同时在跑
`~/.dsh/profiles/` 实测：`idea`、`web` 都是 `Junction → profiles\desktop`。
后果：`DshClientPatch` 把 `dsh-ide-client` 写进 **desktop 的** `node_modules`、把 `dsh-ide-client.patch.yml`
写进 **desktop profile**（只有 `--patch` 才生效，无碍桌面端），但**IDE 与桌面端实际共用同一 profile**（设置/主题/
插件集互相影响），与 `architecture.md §2.4`、`AGENTS.md`「二者不要同时运行」冲突。
当前运行态：`idea64` + 多组 `DeepSeek Harness` 进程同时存在，45231 由 IDE 起的进程占用 → 正在踩这条风险。
建议：要么把 `idea` 恢复成真实独立目录（与 desktop 解耦），要么在文档里明确「本机为共享 profile 模式」并确保不同时运行。

### D.【中】文档漂移（多份，且 HANDOFF 自相矛盾）
- `HANDOFF.md:16` 状态表仍写「约 63 KB zip，32 个单测全绿」「未在 IDE 里跑过」；而 `HANDOFF.md:42,47,399`
  与实测是 **39 条 / 92 KB / 已在真实 IDE 验证（0.1.5/0.1.6）**。
- `deploy-dsh-ide-plugin/SKILL.md`：当前版本写 `0.1.1`、profile 说 `web`。
- `architecture.md §6` 风险表未纳入新风险（profile junction 共用、FAILED→RUNNING 布局）。
- 本文件此前停在 0.1.3（本轮已更新到 0.1.6）。

### E.【中】工程：仓库无版本控制
`Test-Path .git` = False，向上也无父仓库（`git rev-parse` 失败）。有 `.gitignore` 说明原本预期用 git。
无 diff/回滚/历史，风险高。建议 `git init` 并做首次提交（`build/` `.gradle/` `.idea/` 已在忽略内）。

### F.【低】构建产物堆积
`build/libs` 24 个 jar（0.0.1~0.1.6 × base/instrumented/plain）、`build/distributions` 8 个 zip；
根目录空 `META-INF/`（0 子项）。均可清理，不影响源码。

### G.【低】设置写回非线程安全
`DshServerService.kt:120` 在 lifecycle 线程执行 `settings.port = port`，直接改 `PersistentStateComponent`
的当前对象；平台会在保存点持久化，但跨线程改设置对象不是推荐写法（宜 `invokeLater` 或只改内部状态）。

### H.【低】状态文案端口可能与实际不符
`DshServerService.kt:94` `STARTING` 用 `settings.port`（请求值）；`RUNNING`（`:158`）用实际端口。
端口被占向上探测时，启动期状态卡显示的端口与最终端口不一致。

### I.【中·待验证】改动文件卡片取摘要的 GET 路由可能是错的
`client.js:178-193` `changesFileAt` 用 `fetch('api/changes.summary?sessionId=&seq=')`（GET）。
而 `HANDOFF.md §3.4` 明确 0.2.x 的 `/api` 是 **Typert RPC**（走 connection 载体 / `createSharedFetchHandler('/api')`），
**不是**这种简单 GET。该路由是否真实存在未经验证。已兜底（取不到就回退官方 review tab），
但「改动文件卡片直接进 IDEA」可能实际不生效。建议用真实会话验证一次。

### J.【低】无当前会话时「发送到对话」静默丢弃
`client.js:433-437` `insertRefsIntoComposer` 依赖 `persistedSessionId()`（localStorage 当前会话）；
无当前会话（IDE 刚打开、DSH 尚无会话）时直接 `warn` 返回，表现为右键「没反应」。
建议：无会话时自动新建/提示，或至少给用户可见提示。

### K.【信息】测试覆盖结构
- 已覆盖：命令解析(16)、客户端补丁(8)、消息契约(6)、设置迁移(4)、URL 编码(5) = 39。
- **零覆盖**：`DshServerService`（进程/端口/健康检查/退出监控全链路）、`DshIdeService`（排队路由）、
  两个 Action、`DshPanel`（状态机 + 双向桥）。即**最易坏的并发/生命周期/UI 逻辑全部无自动化**，只能靠实机。
  建议优先给 `DshServerService` 的纯逻辑（端口探测、命令拼装、状态机）抽可测函数补单测。

### L.【信息】安全面
监听 `127.0.0.1`、必须带 token、`--no-open`；token 在 query、`ideWorkspace` 在 fragment；未见明显外泄面。
`isDesktopRunning` 用进程名匹配，可信度有限（已在调用处兜底）。

### M.【低】`DshComposerMessage` 行号不做上限校验
`DshComposerMessage.kt:43-46`：`startLine>0` 时下发 `endLine=max(endLine,startLine)`，不校验上限；
超大行号原样传给页面 `(Lx-Ly)`，由模型读文件时自然处理，影响很小。
