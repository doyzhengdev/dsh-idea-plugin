# HANDOFF — DSH for Desktop

> 交接文档。设计细节见 [`.agents/docs/architecture.md`](.agents/docs/architecture.md)；
> 发布流程见 [`.agents/docs/marketplace-publish.md`](.agents/docs/marketplace-publish.md)；
> 协作规则见 [`AGENTS.md`](AGENTS.md)（能直接做的操作一律自己做，不要让用户手动操作）。
> 最后更新：2026-11-05

## 1. 项目定位

- JetBrains IDE 插件（`dsh-idea-plugin`）在工具窗口里用 JCEF 嵌入 DeepSeek Harness 的 Web UI。
- 不自带 Node / dsh CLI：复用官方 **DeepSeek Harness 桌面客户端** 的运行时（Electron 以 node 模式
  运行桌面端内嵌的 `dsh-desktop-host` CLI）。客户端未安装时自动下载安装。
- 共享 `~/.dsh`：凭据、会话、设置与官方桌面端互通。
- 全 IDE 只跑一个 dsh 进程、固定端口 `46231`（被占用时向上探测）；项目通过 URL 参数
  `ideWorkspace` 区分，不按项目起进程。

## 2. 目录与职责

| 部件 | 位置 | 职责 |
|---|---|---|
| IDEA 插件（Kotlin） | `src/main/kotlin/`、`src/main/resources/META-INF/` | 托管 DSH Web（JCEF）、启动/停止宿主、设置弹窗、profile 管理 |
| Marketplace 发布 | `.agents/docs/marketplace-publish.md` | 名称/描述合规、签名、verifier、上传步骤 |
| DSH 侧插件 Host 半边 | `src/main/resources/dsh-plugin-jetbrains/src/index.js` | localStorage 快照路由；首次启动经官方 `configEditor` 写入语言/主题 |
| DSH 侧插件 Client 半边 | `src/main/resources/dsh-plugin-jetbrains/src/client.js` | ideWorkspace 选中项目、文件在 IDE 打开、右键 @引用 插入输入框、工作区过滤 |
| 官方客户端适配 | `src/main/kotlin/.../client/` | 检测/下载安装客户端、定位内嵌 CLI |
| 部署 skill | `.agents/skills/deploy-dsh-ide-plugin/` | 打包 → 关 IDE → 备份 → 替换 → 重启 → 验证 |

## 3. 启动链路

IDE 启动完成（`postStartupActivity`）后，`DshStartupActivity` 在后台调用 `DshServerService.startAsync()`
预热宿主，但**不展示**工具窗口；用户点击右侧 DSH 图标时才创建面板（`DshPanel.onShown()`），此时通常
已经 RUNNING，直接加载页面。`doNotActivateOnStart` 让平台不恢复上次会话遗留的可见状态；启动阶段
平台恢复布局触发的显示由 `DshStartupGate` 拦住，避免重复启动。

`DshServerService` 是应用级单例，`startInternal()` 的顺序：

1. `ProfileManager.ensureLinked(profile)`：确保 `profiles/desktop` 存在（按官方模板），
   并让 `profiles/idea` 成为指向它的目录链接（junction）。目标是普通目录时报错，不删用户数据。
2. `DshBundleBootstrap.install(profileDir)`：
   - 插件实体写到 `<profile>/.dsh-ide/dsh-plugin-jetbrains`；
   - `<profile>/node_modules/dsh-plugin-jetbrains` 建目录链接；
   - `package.json` 的 `devDependencies` 写入 `link:` 依赖；
   - 写 `<profile>/dsh-plugin-jetbrains.patch.yml` 覆盖层。
3. 自动模式（`dsh`）下 `DshClientProvisioner.ensure()` 检测/安装官方客户端，得到 Electron 可执行文件
   与内嵌 CLI 入口。
4. 组装命令：`DeepSeek Harness.exe --expose-internals <cli.js> --profile idea --patch <overlay>
   --host 127.0.0.1 --port 46231 --no-open`。
5. 子进程环境：`ELECTRON_RUN_AS_NODE=1`、`DSH_DESKTOP_NODE_EXECUTABLE`、客户端 runtime/bin 前置到 PATH、
   `DSH_IDE_LOCALE` / `DSH_IDE_THEME`。
6. 读 stdout 的 `dsh web: http://127.0.0.1:<port>/?token=...`，健康检查通过后发布 RUNNING；
   同时启动 Windows 退出守护（IDE 消失时 `taskkill /T` 清理宿主进程树）。

外部覆盖模式：设置里填非 `dsh` 的命令时，走 `DshCommandResolver`（显式路径 → PATH），不安装客户端。

## 4. 关键实现约定

### 4.1 PATH 必须大小写不敏感地写

Windows 环境块的键名通常是 `Path`，`ProcessBuilder.environment()` 的 Map 大小写敏感。
前置客户端 runtime/bin 时必须找到已有的键再写回（`prependToPath`），否则会把 PATH 读成 null，
导致宿主里 `explorer` / `git` / `npm` 全部 ENOENT。

### 4.2 注入插件对 UI 不可见、不可关

- 依赖写在 **devDependencies**：pnpm 的 add/remove 会保留，官方插件管理页与插件市场只读 `dependencies`，
  所以两个 GUI 都不显示本插件。
- 加载走 `--patch` 覆盖层：条目不在 profile patch 索引里，官方 `listPlugins()` 标成 `unaddressable`（只读）。
- `node_modules/<pkg>` 只是链接，实体在 `<profile>/.dsh-ide/`，pnpm 不会把实体挪进 `.ignored`。

### 4.3 首次启动语言 / 主题

Kotlin 只检测 IDEA 语言与明暗主题，经环境变量下发；Host 半边在
`<profile>/.dsh-ide-appearance.json` 不存在时调用官方 `configEditor.edit()` 写入
`locale` / `ui-theme` 的 `config.preference`。已显式设置过 `preference` 时跳过；写完标记后不再覆盖。

### 4.4 localStorage 跨端口快照

Host 半边提供 `/dsh-ide/storage/load|save`，快照存 `<profile.dir>/web-storage/<profile>.json`
（按 profile 而不是 origin）。Client 半边在页面早期同步回填、`setItem` 时 debounce 保存。

### 4.5 IDE 桥

- `ideWorkspace`：页面加载后登记/切换当前项目的工作区。
- 文件打开：覆盖 `sidebarRight.openResource` / `openResourceIn`，JCEF 内点击文件交给 IDE。
- 插入引用：Kotlin 右键动作 → `window.__dshIdeInsertRefs` → composer，显式 `endSpan` 定位。
- 工作区过滤：`ideWorkspace` 由 IDE 固定，侧栏隐藏「工作区」标题、搜索/视图/新建工作区按钮和项目行，
  只保留当前项目的会话分组（属性标记 + MutationObserver）；收起成图标条时 rail 上的
  「添加工作区」「搜索会话」控件也隐藏。
- 顶部操作区：会话 header 右侧的「用资源管理器打开」、更多操作、打开右侧边栏按钮整块隐藏。

### 4.6 启动状态页（HTML）

浏览器在面板初始化时创建：STARTING / FAILED / STOPPED 用 `loadHTML` 显示 CSS 居中的状态页
（资源 `src/main/resources/dsh-ide/status.html`，spinner + 标题 + 说明 + 细进度条），RUNNING 用
`loadURL` 加载 DSH。页面按钮通过 `window.__dshIdeStatusAction('retry' | 'start')` 调回 Kotlin；
状态页的 `onLoadEnd` 不冲刷推送队列（`showingStatus` 标记），避免把引用消息丢进即将被替换的页面。

### 4.7 设置页（HTML）

`DshSettingsDialog` 是 DialogWrapper 内嵌 `JBCefBrowser`，页面资源为
`src/main/resources/dsh-ide/settings.html`（扁平布局，无卡片）。Kotlin 用
`window.__dshIdeSettingsUpdate(state)` 推送状态，页面用 `window.__dshIdeSettingsAction(action)`
触发一键修复 / 重启 / 打开目录。一键修复会先弹确认框；所有 I/O 在后台线程执行，完成后回推状态。
两个页面都由 `ui/HtmlPages` 读取并用 `{{NAME}}` 占位符渲染，主题走 `<html data-theme>`。

### 4.8 启动耗时诊断

启动日志带分阶段耗时：`[启动耗时]`（profile 注入 / 客户端 / 宿主进程 / Web URL / 健康检查）、
`[dsh] 面板创建`、`状态页加载完成`、`运行页面加载完成`。定位慢点时先看这几行。
Windows 启动热路径不要调用 PowerShell：冷启动一次约 1.5s；profile 的 junction 检查已改用 NIO。

## 5. 构建与部署

```bash
cd /e/gitee/doyzheng/dsh-ide
export JAVA_HOME=/d/apps/jdk/jdk-21.0.2      # 默认 JAVA_HOME 是 JDK 8
./gradlew buildPlugin --offline -x test --console=plain
# 产物：build/distributions/dsh-idea-plugin-1.0.0.zip

./gradlew signPlugin -x test --console=plain   # 需要 certificate/（已 gitignore）
# 产物：build/distributions/dsh-idea-plugin-1.0.0-signed.zip
```

迭代期不跑 `test`；只验证测试源码能编译时用 `./gradlew compileTestKotlin --offline`；发版前跑 `./gradlew test --offline`。
部署按 `.agents/skills/deploy-dsh-ide-plugin/`：关闭 IDE → 备份 `plugins/dsh-idea-plugin` →
解压 zip 到 `plugins/` → 重启 IDE → 看 `idea.log` 的 `[dsh]` 行。
发布到 Marketplace 见 `.agents/docs/marketplace-publish.md`（首次必须手动上传 signed zip）。

## 6. 本机环境

- IDE：`C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2`
- 已安装插件：`%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\dsh-idea-plugin`
- 日志：`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`
- DSH home：`C:\Users\ZDY\.dsh`（实际指向 `D:\Users\ZDY\.dsh` 的 junction）
- 官方客户端：`C:\Users\ZDY\AppData\Local\Programs\DeepSeek Harness`
- profile：`~/.dsh/profiles/idea` → `~/.dsh/profiles/desktop`（junction）

## 7. 已验证

- `buildPlugin` 产物包含最新 Kotlin 类与 `dsh-plugin-jetbrains` 资源。
- 实机启动：宿主参数含 `--patch`；`/dsh-plugin-hub/env` 的 pnpm/npm/git 版本有值；
  `POST /dsh-plugin-hub/open-path` 返回 200 且宿主存活；设置→内置插件与插件市场→已安装都不显示本插件；
  IDE 桥 `window.__dshIdeComposerReady === true`。
- 首次语言/主题初始化（临时删 marker 后重启）：日志出现 `[dsh-ide] 已按 IDEA 配置初始化 DSH 语言/主题`，
  `cordis.patch.yml` 的注释、entry 顺序与同级键保留，marker 由宿主重建。
- Marketplace 准备：`buildPlugin` + `signPlugin` 产出 `dsh-idea-plugin-1.0.0-signed.zip`，
  用 `ZipSigningTool verify` 校验通过；`./gradlew test --offline` 全量通过；`verifyPluginStructure` 通过。

## 8. 待办 / 已知限制

- 发布阻塞（需要用户本人）：JetBrains Account 注册、vendor 官网/邮箱、公开源码仓库 URL、Marketplace 截图。
- 官方 GitHub 诊断通道在本机不可达（网络原因，非插件问题）。
- 仍建议人工体验确认：图标观感、暗色主题、多项目窗口切换、composer 正在输入时的引用插入行为。
- DSH 客户端更新若改变 profile/plugin 语义，需要按 `.agents/docs/architecture.md` 的官方源码依据复核。
