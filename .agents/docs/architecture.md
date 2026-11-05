# DSH for Desktop — 架构

> 源码位置：JetBrains 插件 `src/main/kotlin/`；DSH 侧插件 `src/main/resources/dsh-plugin-jetbrains/`。
> 官方参考源码：`E:\github\deepseek-harness`（0.2.2-alpha.3）。本文的结论都可在该仓库对应文件复核。

## 1. 目标与取向

- 在 IDE 工具窗口里用 JCEF 嵌入 DeepSeek Harness Web UI，共享官方桌面端的 `~/.dsh` 数据。
- 全 IDE 只跑**一个** dsh 宿主进程、固定端口（默认 `46231`）。项目维度用 URL 参数 `ideWorkspace`
  区分，而不是每项目一个进程——这样 localStorage / 会话当前项稳定，凭据与会话只维护一份。
- 不自带 Node / dsh CLI，复用官方桌面客户端携带的运行时；未安装时自动下载安装。

## 2. 进程与数据模型

```
IDEA (JVM)
 └─ DshServerService（应用级单例）
     └─ DeepSeek Harness.exe --expose-internals <cli.js> --profile idea
          --patch <profile>/dsh-plugin-jetbrains.patch.yml
          --host 127.0.0.1 --port 46231 --no-open
          │
          ├─ profile：~/.dsh/profiles/idea → ~/.dsh/profiles/desktop（junction）
          └─ 数据：~/.dsh（凭据 / sessions / storages 与桌面端共享）
```

- **profile 共享**：`idea` 链接到桌面端的 `desktop`，两边使用同一份插件配置与会话；
  桌面端缺失时按官方模板（`@deepseek-ai/dsh-base` + `@deepseek-ai/dsh-web-app`）创建。
- **固定端口**：origin 稳定，localStorage 不因端口漂移；端口被占用时启动阶段向上探测并写回设置。
- **单进程**：官方 Loader 的 workspace / session 都按实体 id 与绝对路径工作，同一进程内多项目互不干扰。

## 3. 启动链路（`DshServerService.startInternal`）

IDE 启动完成后（`postStartupActivity`）`DshStartupActivity` 在后台调用 `DshServerService.startAsync()`
预热宿主，但不展示工具窗口：`DshServerService` 是应用级单例，工具窗口 content 在用户点击 DSH 图标
首次显示时创建（`doNotActivateOnStart` 让平台不重复恢复上次会话遗留的可见状态，启动阶段恢复
布局触发的显示由 `DshStartupGate` 拦住）；启动失败或手动停止时 `DshPanel.onShown()` 会重试。

`startInternal()` 的顺序：1. `ProfileManager.ensureLinked("idea")`：来源 `desktop` 不存在则按模板创建；目标缺失建 junction，
   断链重建，普通目录报错（不删除用户数据）。
2. `DshBundleBootstrap.install(profileDir)`：注入 DSH 侧插件，返回 `--patch` 覆盖层路径（见 §5）。
3. 自动模式（设置值 `dsh`）下 `DshClientProvisioner.ensure()` 定位或下载安装官方客户端，
   拿到 Electron 可执行文件与内嵌 CLI 入口。
4. `ProcessBuilder`：cwd 为 `user.home`；环境变量：
   - `ELECTRON_RUN_AS_NODE=1`、`DSH_DESKTOP_NODE_EXECUTABLE=<client.exe>`；
   - 客户端 `runtime/bin` **前置到 PATH**（必须按键名不区分大小写写回，见 §3.1）；
   - `DSH_IDE_LOCALE` / `DSH_IDE_THEME`（首次语言/主题，见 §7）。
5. 读 stdout 的 `dsh web: http://127.0.0.1:<port>/?token=...`，带 token 健康检查（不跟随重定向），
   通过后发布 `RUNNING`。
6. Windows 退出守护：`Wait-Process -Id <IDE pid>` 后 `taskkill /T /F`，覆盖 IDE 被强杀的情况。

外部覆盖模式：设置里填非 `dsh` 的命令 → `DshCommandResolver`（显式路径 → PATH），不检测/安装客户端。

### 3.1 PATH 写法

Windows 环境块通常保存为 `Path`，而 `ProcessBuilder.environment()` 返回的 Map **大小写敏感**。
必须按 `equals("PATH", ignoreCase = true)` 找到已有键再写回；直接写 `env["PATH"]` 会把原值读成 null，
宿主里 `explorer` / `git` / `npm` 全部 ENOENT。

## 4. JCEF 面板与 IDE 桥

`ui/DshPanel.kt` 负责创建 `JBCefBrowser`，注入 Kotlin ↔ 页面桥：

| 能力 | 实现 |
|---|---|
| 打开外部链接 | 系统浏览器优先，失败回退内置 `DshExternalBrowserDialog` |
| 在 IDE 打开文件 | `window.__dshIdeOpenFile`，支持 line/column |
| 插入引用 | `window.__dshIdeInsertRefs`（composer 显式 `endSpan`，避免抢占用户光标） |
| 打开配置文件 | `window.__dshIdeOpenFile` + 设置页「打开配置文件」按钮拦截 |
| 右键菜单本地化 | `DshJcefContextMenuHandler` |
| 启动状态页 | STARTING / FAILED / STOPPED 时 `loadHTML` 居中状态页（`dsh-ide/status.html`）；按钮走 `window.__dshIdeStatusAction` |

Client 半边（`client.js`）在页面侧：

- `ideWorkspace`：登记/切换到当前项目的工作区；侧栏隐藏「工作区」标题、搜索/视图/新建工作区按钮
  与项目行，只保留当前项目的会话分组（属性标记 + MutationObserver）；侧栏收起成图标条时，
  rail 上的「添加工作区」「搜索会话」控件同样隐藏；
- 会话顶部右侧操作区（用资源管理器打开、更多操作、打开右侧边栏）也整块隐藏，这些动作由 IDE 接管；
- 覆盖 `sidebarRight.openResource` / `openResourceIn`：文件树点击、右键「在 IDE 中打开」都走 IDE；
- 外部链接统一交给 Kotlin 打开；composer 引用插入走 `__dshIdeInsertRefs`。

## 5. DSH 侧插件注入（`DshBundleBootstrap`）

布局：

```
<profile>/.dsh-ide/dsh-plugin-jetbrains/     插件实体（node_modules 之外）
<profile>/node_modules/dsh-plugin-jetbrains  指向实体的目录链接
<profile>/package.json  devDependencies       "dsh-plugin-jetbrains": "link:./.dsh-ide/..."
<profile>/dsh-plugin-jetbrains.patch.yml     --patch 覆盖层
```

选择这套写法的原因（官方源码依据）：

- pnpm 的 `add` / `remove` 会保留 **devDependencies**，不会把目录当 extraneous 挪进
  `node_modules/.ignored`；
- 官方插件管理器的 `readBundles()` 只读 `manifest.dependencies` + `dsh.profile.bundles` +
  安装包自带 + 官方目录（`packages/boot/plugin-manager/src/index.ts`）；
  第三方插件市场的 `/installed` 也只读 `dependencies`（`dsh-plugin/lib/http/catalog.js`）。
  因此 devDependencies 里的本插件在两个 GUI 都不显示；
- 用 `--patch` 覆盖层加载时，条目不在 profile patch 索引里，官方 `listPlugins()` 会标成
  `unaddressable`（只读），即使出现在运行时 inventory 也不能开关。

不采用「做成内置插件」：`HIDDEN_BUNDLES` / `PROTECTED_PLUGINS` 硬编码在官方客户端的
`@deepseek-ai/dsh-official-bundles`（app.asar 内），修改会被客户端更新覆盖。

## 6. localStorage 跨端口快照

- Host 半边（`src/index.js`）提供 `GET /dsh-ide/storage/load`、`POST /dsh-ide/storage/save`；
  快照存 `<profile.dir>/web-storage/<profile>.json`，按 profile 而不是按 origin。
- Client 半边在模块加载时同步 XHR 拉取并回填缺失键；监听 `Storage.prototype.setItem/removeItem/clear`，
  600ms debounce 保存；`beforeunload` / 页面隐藏用 `sendBeacon` 兜底。
- 快照只覆盖 localStorage；IndexedDB / sessionStorage 不在范围内。

## 7. 首次启动语言 / 主题

- Kotlin `DshProfileAppearance`：`Locale.getDefault()` → DSH 支持语言（zh/en/ja/es/fr/de/ru/pt，其余 en）；
  `JBColor.isBright()` → `light` / `dark`；通过环境变量下发。
- Host 半边 `initIdeAppearance`：`<profile>/.dsh-ide-appearance.json` 不存在时，
  等官方 `configEditor` 与 `locale` / `ui-theme` entry 就绪，调用
  `configEditor.edit(entry, current => ({ ...current, preference: value }))` 写入。
  已有 `override.preference` 时跳过；全部成功才写标记，失败留待下次启动重试。
- 官方 `ConfigEditor`（`packages/boot/config-editor/src/index.ts`）基于 yaml 文档模型编辑，
  保留注释、缩进、`!!js` 与同级键（如 `fontSize`），并在 `hmr.runExclusive` + 文件锁内串行化。

## 8. 设置弹窗与管理功能

`settings/DshSettingsDialog.kt` 是独立 `DialogWrapper`，内容为内嵌 `JBCefBrowser` 的 HTML 页面
（`src/main/resources/dsh-ide/settings.html`），由工具窗口右上角齿轮打开。双向通信：

- Kotlin → 页面：`window.__dshIdeSettingsUpdate(state)`（监听地址 / 端口 / profile、客户端目录与版本、
  Node 版本、busy 与状态文案）；
- 页面 → Kotlin：`window.__dshIdeSettingsAction(action)`：
  - `repair`：先弹窗确认，再停宿主 → 检测/安装客户端 → 修复 profile 链接 → 重新注入插件 → 重启宿主；
  - `restart`：`DshServerService.restartAsync()`；
  - `open-profile` / `open-client`：用系统文件管理器打开目录。

I/O 全部在后台线程执行，完成后把新状态推回页面。

启动状态页同样使用 HTML（`src/main/resources/dsh-ide/status.html`）：`DshPanel` 在 STARTING / FAILED / STOPPED 时
`loadHTML` 一个 CSS 居中的状态页（spinner + 进度条 + 重试/启动按钮），RUNNING 时 `loadURL` 加载 DSH；
按钮通过 `window.__dshIdeStatusAction(action)` 调回 Kotlin。两个页面都由 `ui/HtmlPages` 读取资源，
用 `{{NAME}}` 占位符替换动态内容（主题通过 `<html data-theme>` 切换）。

## 9. 构建、部署、验证

```bash
cd /e/gitee/doyzheng/dsh-ide
export JAVA_HOME=/d/apps/jdk/jdk-21.0.2
./gradlew buildPlugin --offline -x test --console=plain
```

- 产物：`build/distributions/dsh-idea-plugin-0.8.0.zip`；内层 `dsh-idea-plugin/lib/*.jar`。
- 部署：`.agents/skills/deploy-dsh-ide-plugin/`（关 IDE → 备份 → 替换 → 重启 → 查
  `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log` 的 `[dsh]` 行）。
- 快速验证：
  - `grep "启动 DSH" idea.log` 应包含 `--profile idea --patch ... --host 127.0.0.1 --port 46231`；
  - `curl http://127.0.0.1:46231/dsh-plugin-hub/env` 的 pnpm/npm/git 版本应有值（PATH 正常）；
  - CDP：`node .agents/skills/deploy-dsh-ide-plugin/scripts/cdp-eval.mjs "window.__dshIdeComposerReady === true"`。

## 10. 已知限制

- 宿主进程与桌面端不能同时使用同一个 profile（Windows 文件锁）；同一时间只保证一个宿主。
- 官方 GitHub 诊断通道在受限网络下不可达（本地网络问题，非插件缺陷）。
- JCEF 的原生右键菜单语言由 `CefSettings.locale` 在启动时决定，运行期无法切换。
