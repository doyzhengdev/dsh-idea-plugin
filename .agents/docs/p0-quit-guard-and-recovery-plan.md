# P0 落地改造方案：退出任务检查 + 诊断/禁用插件恢复

> 关联：`desktop-parity-analysis.md` §5.3、§5.4。
> 目标：在**不改变现有启动/桥接架构**的前提下，补上官方桌面端最重要、成本最低的两项能力：
> 1) **停止/重启前检查运行中任务**，避免误杀正在跑的 agent；
> 2) **启动/运行失败可诊断、可一键恢复**（禁用第三方插件 + 备份 patch 后重启）。
>
> 本文件是改造方案（含可直接落地的代码骨架），尚未提交实现。涉及文件见 §6。

## 0. 范围与不做什么

**做**
- Host 侧新增只读任务检查接口 `GET /dsh-ide/tasks/inspect`（复用 official `hasDesktopActiveTasks` 判定）。
- Kotlin 侧 `DshServerService.inspectActiveTasks()`；在所有「会停掉 dsh」的入口前弹确认
  （重启服务 / 一键修复 / 检查更新需要重装 / 同步 profile）。
- 启动失败/意外退出时写**诊断报告**文件（含最近输出、命令、版本、profile bundles），FAILED 状态卡提供入口。
- FAILED 状态卡新增「禁用第三方插件并重启」：备份 `cordis.patch.yml`、把 `dsh.profile.bundles`
  精简到内置集合（保留 `dsh-plugin-jetbrains`）、保留 `node_modules`。
- IDE 进程退出：注册 `AppLifecycleListener.appClosing()` 做**尽力提示**（IntelliJ 不提供否决权，见 §5）。

**不做（后续阶段）**
- 优雅 shutdown 协议（`POST /dsh-ide/shutdown` 让 Host 自己 `application.shutdown`）——P1 再做。
- 崩溃报告上限/操作历史（operation-journal）——P2。
- `fileActions` / `__DSH_HOST_PATHS__` / `__DSH_DIRECTORY_PICKER__` —— 见分析文档 §4，另开任务。

## 1. Host 侧：任务检查接口（`dsh-plugin-jetbrains/src/index.js`）

在现有 localStorage 路由旁新增只读接口。判定逻辑照抄官方
`apps/desktop-host/src/update-tasks.ts:16-21` 与 `quit-inspection.ts:23-39`。

### 1.1 新增常量与工具

```js
const TASKS_INSPECT_PATH = '/dsh-ide/tasks/inspect'

// 只放行本机回环：给 Kotlin 内部调用（无浏览器 cookie）。只读接口，风险低。
const isLoopback = (req) => {
  const address = (req.socket && req.socket.remoteAddress) || ''
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

// official: hasDesktopActiveTasks —— running agent、排队消息、running/stopping job
const hasActiveTasks = (agents, jobs) => {
  const live = agents.list()
  const agentActive = live.some((agent) =>
    agent.status === 'running'
    || (agent.inbox && agent.inbox.nextTurn && agent.inbox.nextTurn.length > 0)
    || (agent.inbox && agent.inbox.nextStep && agent.inbox.nextStep.length > 0))
  if (agentActive) return true
  return [undefined, ...live].some((agent) =>
    jobs.list(agent && agent.id).some((job) => job.status === 'running' || job.status === 'stopping'))
}

// official: quit-inspection 的 scheduledTasks（已挂定时器提醒）
const hasScheduledTasks = async (ctx, agents) => {
  if (typeof ctx.waterfall !== 'function') return false
  for (const agent of agents.list()) {
    const activity = await ctx.waterfall(
      'workspace/session-activity', { sessionId: agent.id }, () => Promise.resolve([]))
    if (Array.isArray(activity) && activity.some((entry) => entry && entry.kind === 'schedule')) return true
  }
  return false
}
```

### 1.2 注册路由（`apply` 内，紧跟 storage 路由之后）

```js
  registerRoute(ctx, {
    kind: 'exact',
    path: TASKS_INSPECT_PATH,
    handler: async (req, res) => {
      if (!isLoopback(req)) { res.statusCode = 403; res.end(); return }
      if (req.method !== 'GET') { sendJson(res, 405, { error: 'method not allowed' }); return }
      const agents = serviceOf(ctx, 'agents')
      const jobs = serviceOf(ctx, 'jobs')
      if (agents === undefined || jobs === undefined
        || typeof agents.list !== 'function' || typeof jobs.list !== 'function') {
        // 服务不可用 = 无法判断，上层按「可能有任务」处理
        sendJson(res, 200, { available: false, reason: 'task services unavailable' })
        return
      }
      try {
        const activeTasks = hasActiveTasks(agents, jobs)
        const scheduledTasks = await hasScheduledTasks(ctx, agents)
        sendJson(res, 200, { available: true, activeTasks, scheduledTasks })
      } catch (error) {
        sendJson(res, 200, { available: false, reason: String((error && error.message) || error) })
      }
    },
  }, `dsh-ide: GET ${TASKS_INSPECT_PATH}`)
```

安全说明：该接口只读、仅回环地址；P1 可改为「Kotlin 侧沿用 303 拿到的 `dsh-auth-*` cookie + 同源校验」，
但这需要 `DshServerService` 保存 cookie（见 §2.3 的可选加固）。

## 2. Kotlin 侧：任务检查与确认

### 2.1 `DshServerService` 新增数据类与方法

```kotlin
data class DshTaskInspection(
    val available: Boolean,
    val activeTasks: Boolean,
    val scheduledTasks: Boolean,
    val detail: String? = null,
)

/**
 * 查询 Host 当前是否有可中断的任务。读不到 / 服务不可用 → available=false，
 * 调用方必须按「可能有任务」处理（与官方 quit 检查同口径）。
 * 在后台线程调用（内部有网络 I/O）。
 */
fun inspectActiveTasks(timeoutMs: Int = 2000): DshTaskInspection {
    val url = currentStatus().url ?: return DshTaskInspection(false, true, false, "DSH 未运行")
    return runCatching {
        val base = java.net.URI(url)
        val target = java.net.URI(base.scheme, null, base.host, base.port, "/dsh-ide/tasks/inspect", null, null)
        val connection = (target.toURL().openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                return@runCatching DshTaskInspection(false, true, false, "HTTP ${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val available = json.get("available")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            if (!available) {
                DshTaskInspection(false, true, false, json.get("reason")?.let { if (it.isJsonPrimitive) it.asString else null })
            } else {
                DshTaskInspection(
                    available = true,
                    activeTasks = json.get("activeTasks")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                    scheduledTasks = json.get("scheduledTasks")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                )
            }
        } finally { connection.disconnect() }
    }.getOrElse { DshTaskInspection(false, true, false, it.message ?: it.toString()) }
}
```

### 2.2 `DshSettingsDialog`：停止前确认

`runTask` 的 `block` 已在后台线程执行，因此在 block 内先做（后台）网络检查，再把「是否继续」对话框
投递到 EDT 并等待结果。

```kotlin
/** 在后台线程调用；内部把确认框投递到 EDT 并等待。返回 true 才允许停止。 */
private fun confirmStopIfBusy(action: String): Boolean {
    val inspection = runCatching { DshServerService.getInstance().inspectActiveTasks() }
        .getOrElse { DshTaskInspection(false, true, false, it.message) }
    var allow = false
    val latch = java.util.concurrent.CountDownLatch(1)
    SwingUtilities.invokeLater {
        allow = when {
            !inspection.available -> Messages.showYesNoDialog(
                project,
                "无法确认 DSH 当前是否有运行中的任务。继续$action可能中断正在进行的对话。是否继续？",
                "DSH", "继续", "取消", null) == Messages.YES
            !inspection.activeTasks && !inspection.scheduledTasks -> true
            else -> {
                val what = buildString {
                    if (inspection.activeTasks) append("正在运行的对话/工具/后台任务")
                    if (inspection.scheduledTasks) {
                        if (isNotEmpty()) append("、")
                        append("已挂起的定时提醒")
                    }
                }
                Messages.showYesNoDialog(
                    project, "$what 将被中断。是否继续$action？",
                    "DSH", "继续$action", "取消", null) == Messages.YES
            }
        }
        latch.countDown()
    }
    latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
    return allow
}
```

改造三个入口（都在 `runTask`/后台线程内）：

```kotlin
private fun restartService() {
    runTask("正在检查运行中的任务…") {
        if (!confirmStopIfBusy("重启 DSH 服务")) return@runTask "已取消重启"
        DshServerService.getInstance().restartAsync()
        "已请求重启 DSH 服务（后台进行）"
    }
}

private fun repairAll() {
    runTask("正在检查运行中的任务…") {
        if (!confirmStopIfBusy("一键修复")) return@runTask "已取消一键修复"
        DshServerService.getInstance().stopBlocking()
        // ...原有逻辑不变
        "一键修复完成（Node ... / dsh ...），已请求重启 DSH 服务"
    }
}

private fun checkCliUpdate() {
    runTask("正在检查 DSH CLI 更新…") {
        // 仅在确实需要重装（version != current）时确认
        // 原有检查版本逻辑保留；进入重装分支前：
        //   if (!confirmStopIfBusy("更新 DSH CLI")) return@runTask "已取消更新"
    }
}
```

> 注意：`restartService()` 目前直接 `restartAsync()`，需改为走 `runTask` 才能后台检查 + 弹框；
> 同时 `restartServiceButton` 的 busy 态由 `setBusy` 统一处理。

### 2.3（可选加固）用 cookie 走同源校验

`DshServerService.awaitHealthy(url)` 现在通过「不跟随重定向看 303」探测健康；可顺手保存
`Set-Cookie` 的 `dsh-auth-*` 部分，`inspectActiveTasks` 请求时带上 `Cookie` 头，并删掉 index.js 的
`isLoopback` 放行、改回 `rejectUntrusted`。P0 暂用回环放行即可，P1 再加固。

## 3. 诊断报告（Kotlin，新文件）

新增 `src/main/kotlin/dev/doyzheng/dsh/ide/DshDiagnostics.kt`：

```kotlin
package dev.doyzheng.dsh.ide

import com.intellij.openapi.application.ApplicationInfo
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

object DshDiagnostics {
    private const val KEEP = 10

    fun directory(): Path = DshProfileBootstrap.dshHome().resolve("idea").resolve("diagnostics")

    fun write(
        state: String,
        detail: String?,
        command: List<String>,
        profile: String,
        nodeVersion: String?,
        cliVersion: String?,
        recentOutput: List<String>,
    ): Path? = runCatching {
        val dir = directory()
        Files.createDirectories(dir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(Date())
        val file = dir.resolve("crash-$stamp-startup.log")
        val build = runCatching { ApplicationInfo.getInstance().build.asString() }.getOrDefault("unknown")
        val profileDir = DshProfileBootstrap.profileDir(profile)
        val bundles = runCatching {
            Files.readString(profileDir.resolve("package.json"), Charsets.UTF_8)
        }.getOrNull()?.let { text ->
            runCatching {
                com.google.gson.JsonParser.parseString(text).asJsonObject
                    .getAsJsonObject("dsh")?.getAsJsonObject("profile")?.getAsJsonArray("bundles")?.toString()
            }.getOrNull()
        }
        val body = buildString {
            appendLine("time: ${Date()}")
            appendLine("state: $state")
            appendLine("detail: ${detail ?: ""}")
            appendLine("idea build: $build")
            appendLine("plugin: 0.7.2") // 与 plugin.xml 保持一致
            appendLine("os: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            appendLine("profile: $profile ($profileDir)")
            appendLine("bundles: ${bundles ?: "(unreadable)"}")
            appendLine("node: ${nodeVersion ?: "?"}")
            appendLine("dsh: ${cliVersion ?: "?"}")
            appendLine("command: ${command.joinToString(" ")}")
            appendLine()
            appendLine("--- recent output (last ${recentOutput.size} lines) ---")
            recentOutput.forEach { appendLine(it) }
        }
        Files.writeString(file, body, Charsets.UTF_8)
        prune(dir)
        file
    }.getOrNull()

    fun latest(): Path? = runCatching {
        Files.newDirectoryStream(directory(), "crash-*.log").use { stream ->
            stream.toList().maxByOrNull { it.fileName.toString() }
        }
    }.getOrNull()

    private fun prune(dir: Path) {
        runCatching {
            Files.newDirectoryStream(dir, "crash-*.log").use { stream ->
                stream.toList().sortedBy { it.fileName.toString() }
                    .dropLast(KEEP).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
```

### 3.1 `DshServerService` 捕获最近输出 + 失败时写诊断

- 新增字段：
  ```kotlin
  @Volatile private var lastCommand: List<String> = emptyList()
  @Volatile private var lastProfile: String = ""
  private val recentOutput = ArrayDeque<String>()
  fun recentOutput(): List<String> = synchronized(recentOutput) { recentOutput.toList() }
  private fun appendOutput(line: String) {
      synchronized(recentOutput) {
          recentOutput.addLast(line)
          while (recentOutput.size > 200) recentOutput.removeFirst()
      }
  }
  ```
- `awaitUrl` 读线程里，每读到一行 `appendOutput(line)`（保留现有 `log.info("[dsh] $line")`）。
- `startInternal` 组命令后记录 `lastCommand = tokens; lastProfile = profile`。
- 新增统一失败出口，替换现有 5 处 `publish(DshStatus(DshState.FAILED, detail = ...))`：
  ```kotlin
  private fun fail(detail: String) {
      val runtime = runCatching { DshRuntimeProvisioner.currentInstalled() }.getOrNull()
      runCatching {
          DshDiagnostics.write(
              state = "failed", detail = detail, command = lastCommand, profile = lastProfile,
              nodeVersion = runtime?.node?.version, cliVersion = runtime?.cli?.version,
              recentOutput = recentOutput(),
          )
      }
      publish(DshStatus(DshState.FAILED, detail = detail))
  }
  ```
  替换点：`:120`（无法创建 profile）、`:156`（准备运行时失败）、`:194`（启动失败）、
  `:208`（等待 URL 超时/进程提前退出）、`:215`（健康检查失败）；`watch()` 的意外退出也改调 `fail(...)`。

## 4. 禁用第三方插件恢复（Kotlin）

### 4.1 `ProfileManager.disableThirdPartyBundles`

```kotlin
private val ALLOWED_BUNDLES = setOf(
    "@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", IDE_BUNDLE_NAME)

/**
 * 官方 sanitizeProfile 的 Kotlin 等价物：
 *  - 把 cordis.patch.yml 改名为 cordis.patch.yml.bak-<stamp>（下次启动会重新生成空 patch）；
 *  - 只保留内置 bundle（base/web-app + IDE 桥插件），移除第三方 bundle；
 *  - 不改 node_modules、已安装包与备份。
 */
fun disableThirdPartyBundles(target: String): ProfileResult {
    val root = profilesRoot()
    val targetDir = root.resolve(target)
    if (!Files.isDirectory(targetDir)) return ProfileResult(false, error = "profile 不存在：$targetDir")
    val steps = mutableListOf<String>()

    val patch = targetDir.resolve("cordis.patch.yml")
    if (Files.exists(patch)) {
        val backup = targetDir.resolve("cordis.patch.yml.bak-${Fs.stamp()}")
        runCatching { Files.move(patch, backup) }
            .onSuccess { steps += "已备份 cordis.patch.yml → $backup" }
            .onFailure { return ProfileResult(false, steps, "备份 patch 失败：${it.message}") }
    } else {
        steps += "没有 cordis.patch.yml 可备份"
    }

    val manifest = targetDir.resolve("package.json")
    val parsed = readJson(manifest) ?: return ProfileResult(false, steps, "package.json 无法解析：$manifest")
    val dsh = parsed.getAsJsonObject("dsh") ?: com.google.gson.JsonObject().also { parsed.add("dsh", it) }
    val profile = dsh.getAsJsonObject("profile") ?: com.google.gson.JsonObject().also { dsh.add("profile", it) }
    val original = profile.getAsJsonArray("bundles")
        ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
    val kept = original.filter { it in ALLOWED_BUNDLES }.toMutableList()
    for (required in listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", IDE_BUNDLE_NAME)) {
        if (required !in kept) kept += required
    }
    val rebuilt = com.google.gson.JsonArray().apply { kept.forEach { add(it) } }
    profile.add("bundles", rebuilt)
    val text = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed) + "\n"
    Files.writeString(manifest, text, Charsets.UTF_8)
    steps += "bundles 已精简为：${kept.joinToString(", ")}"

    DshBundleBootstrap.install(targetDir)
    return ProfileResult(true, steps)
}
```

> 为什么必须保留 `dsh-plugin-jetbrains`：它承载 IDE 桥；即使第三方插件被禁用，IDE 功能不能一起消失。
> 其 overlay `dsh-plugin-jetbrains.patch.yml` 由 Kotlin 单独注入，不受 `cordis.patch.yml` 备份影响。

### 4.2 `DshPanel`：FAILED 状态卡加两个按钮

- 现有 `retryRow` 只有「重试」。改为三按钮：`重试` / `查看诊断` / `禁用第三方插件并重启`。
- `查看诊断`：`DshDiagnostics.latest()` → 系统文件管理器定位（Windows `explorer.exe /select,<file>`；
  macOS `open -R`；其他 `xdg-open <dir>`）；没有报告则提示。
- `禁用第三方插件并重启`：
  ```kotlin
  private fun recoverFromFailed() {
      if (Messages.showYesNoDialog(project,
              "将停止 DSH、备份 profile 的 cordis.patch.yml，并只保留内置 bundle（含 DSH IDE 桥插件）后重启。已安装的插件包不会被删除。是否继续？",
              "禁用第三方插件并重启", "继续", "取消", null) != Messages.YES) return
      ApplicationManager.getApplication().executeOnPooledThread {
          val service = DshServerService.getInstance()
          service.stopBlocking()
          val profile = DshSettings.getInstance().current.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
          val result = ProfileManager.disableThirdPartyBundles(profile)
          SwingUtilities.invokeLater {
              if (result.ok) service.startAsync()
              else Messages.showErrorDialog(project, result.error ?: result.steps.joinToString("；"), "DSH 恢复")
          }
      }
  }
  ```
  新增 import：`com.intellij.openapi.ui.Messages`、`dev.doyzheng.dsh.ide.jetbrains.ProfileManager`。

## 5. IDE 进程退出的任务保护（重要限制）

官方 Electron **拥有进程**，可在 `before-quit` 里阻塞并弹确认。IntelliJ **不向插件暴露否决权**：
`com.intellij.ide.AppLifecycleListener.appClosing()` 是同步通知，无法取消退出。因此：

- **可靠范围（P0 交付）**：所有「插件主动停 dsh」的入口都前置确认（§2.2）。这是用户最常触发的路径。
- **尽力而为**：注册 `AppLifecycleListener`，在 `appClosing()` 时若仍有活动任务，弹一个**信息提示**
  （「有 N 个运行中的任务，DSH 将随 IDE 退出而停止」），不声称能阻止退出。
  注册方式：在 `plugin.xml` 的 `<applicationListeners>` 加一行；或在任意 application 级服务里
  `ApplicationManager.getApplication().messageBus.connect().subscribe(AppLifecycleListener.TOPIC, ...)`。
- **兜底**：保留现有 `startExitWatchdog`（IDE 被强杀时杀 dsh 进程树）与 `dispose()` 清理。
- 若产品上确实需要「IDE 退出确认」，需要走 IDE 自身的退出确认（`Settings → Appearance` 的
  confirm exit 机制，插件无法注入文案），或改为「DSH 服务常驻、IDE 退出不停」的另一种产品形态后置评估。

## 6. 改动文件清单

| 文件 | 改动 |
|---|---|
| `src/main/resources/dsh-plugin-jetbrains/src/index.js` | 新增 `isLoopback` / `hasActiveTasks` / `hasScheduledTasks` / `/dsh-ide/tasks/inspect` 路由 |
| `src/main/kotlin/dev/doyzheng/dsh/ide/DshServerService.kt` | 新增 `DshTaskInspection`、`inspectActiveTasks()`、`lastCommand/lastProfile/recentOutput`、`fail()`；替换 5 处 FAILED publish + `watch()` |
| `src/main/kotlin/dev/doyzheng/dsh/ide/DshDiagnostics.kt` | 新增：诊断报告写入/轮转/定位 |
| `src/main/kotlin/dev/doyzheng/dsh/ide/settings/DshSettingsDialog.kt` | 新增 `confirmStopIfBusy()`；`restartService/repairAll/checkCliUpdate` 前置确认 |
| `src/main/kotlin/dev/doyzheng/dsh/ide/ui/DshPanel.kt` | FAILED 卡新增「查看诊断」「禁用第三方插件并重启」 |
| `src/main/kotlin/dev/doyzheng/dsh/ide/jetbrains/ProfileManager.kt` | 新增 `disableThirdPartyBundles()` |
| `src/main/resources/META-INF/plugin.xml`（可选） | 注册 `AppLifecycleListener` 尽力提示 |
| `HANDOFF.md` / `CHANGELOG.md` / `plugin.xml` 版本号 | 记录本次变更并升版本 |

## 7. 验证计划

**单元测试（Kotlin）**
- `DshDiagnosticsTest`：写入生成文件、超过 10 份轮转、`latest()` 取最新。
- `ProfileManagerTest.disableThirdPartyBundles`：临时 profile 造 `cordis.patch.yml` + 第三方 bundle，
  断言 patch 被改名、bundles 只剩内置集合且含 `dsh-plugin-jetbrains`、`node_modules` 未被删。
- `DshTaskInspection` 解析：可用/不可用/字段缺失三态。

**手动冒烟**
1. 在 IDE 里发起一个长回答，打开设置 → 点「重启 DSH 服务」→ 应弹「正在运行的对话…将被中断」；选取消则服务不动。
2. 无任务时点重启 → 不弹确认，直接重启。
3. 人为让 dsh 启动失败（把 dsh 版本设成不存在）→ FAILED 卡出现「查看诊断」；诊断文件有命令/输出。
4. 在 profile 里加一个坏插件导致启动失败 → 点「禁用第三方插件并重启」→ 备份 patch 出现、服务恢复。
5. 运行 `./gradlew test`（JDK21）全绿；`./gradlew buildPlugin --offline` 出包。

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `/dsh-ide/tasks/inspect` 回环放行被本机其他进程访问 | 只读、无副作用；P1 改 cookie 同源校验 |
| `hasActiveTasks` 对子代理/审批态判定不全 | 逐字段对齐 official `update-tasks.ts`；读不到一律按「有任务」 |
| 重写 `package.json` 丢字段/格式 | 用 Gson 保留所有字段，只替换 `dsh.profile.bundles`；恢复前有 patch 备份 |
| 误备份/误删 | 只 `rename` patch，不删除；`node_modules` 不动；操作前有确认框 |
| IDE 退出无法否决 | 已明确限制，只做提示；不夸大能力 |

**回滚**：本次改动均为新增接口/方法/按钮与失败出口替换，不改启动命令与协议；回滚 = 还原上述文件 +
删除 `DshDiagnostics.kt`。诊断文件与被备份的 `cordis.patch.yml.bak-*` 可手动删除。
