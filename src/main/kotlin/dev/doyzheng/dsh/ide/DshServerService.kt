package dev.doyzheng.dsh.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import dev.doyzheng.dsh.ide.client.DshClientInstall
import dev.doyzheng.dsh.ide.client.DshClientProvisioner
import dev.doyzheng.dsh.ide.jetbrains.ProfileManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class DshState { STOPPED, STARTING, RUNNING, FAILED }

data class DshStatus(
    val state: DshState = DshState.STOPPED,
    /** 可直接给 JCEF 加载的完整 URL（带 token）。RUNNING 时有效。 */
    val url: String? = null,
    val port: Int = 0,
    val detail: String? = null,
)

/**
 * 全 IDE 唯一的 dsh 进程宿主（应用级单例）。
 *
 * 设计取向（与 zcode-ide 一致，与 deepseek-harness-for-ide 相反）：
 *
 * 1. **一个进程服务所有项目**。DSH 的 workspace 是「UUID + 绝对 path」的持久实体，
 *    会话创建时把 cwd 写进不可变 header，工具执行用该会话的 workingDirectory，
 *    所以同一进程内多个项目各用各的目录是完全成立的（官方桌面端就是这么用的）。
 * 2. **共享官方 home**：不设置 `DSH_HOME`，直接用 `~/.dsh`，
 *    这样桌面端聊过的会话、配好的凭据与设置，IDE 里直接可见。
 * 3. **固定端口**：origin 稳定，localStorage（含当前会话）不会因端口变化而丢。
 * 4. **profile 独立**：用 `idea` profile，不和桌面端的 `desktop` profile、
 *    也不和用户手跑的 `web` profile 抢插件配置；sessions/storages/凭据仍然共享。
 */
@Service(Service.Level.APP)
class DshServerService : Disposable {

    private val log = thisLogger()

    private val lifecycle: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsh-ide-lifecycle").apply { isDaemon = true }
        }

    private val status = AtomicReference(DshStatus())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(DshStatus) -> Unit>()
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    /** IDE 退出守护：IDE(JVM) 进程消失后强杀 dsh 进程树，覆盖 IDE 被强杀/崩溃、dispose 未执行的情况。 */
    @Volatile
    private var watchdog: Process? = null

    @Volatile
    private var stopRequested = false

    fun currentStatus(): DshStatus = status.get()

    fun addListener(listener: (DshStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DshStatus) -> Unit) {
        listeners.remove(listener)
    }

    /** 幂等启动：已在运行或启动中则直接返回。 */
    fun startAsync() = lifecycle.execute { startInternal() }

    fun stopAsync() = lifecycle.execute { stopInternal() }

    /** 同步等待停止完成（设置弹窗重装运行时/替换 profile 前用），超时返回 false。 */
    fun stopBlocking(timeoutMs: Long = 30_000): Boolean = runCatching {
        lifecycle.submit { stopInternal() }.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    }.getOrElse {
        log.warn("等待 DSH 停止失败", it)
        false
    }

    fun restartAsync() = lifecycle.execute {
        stopInternal()
        startInternal()
    }

    // ---------------------------------------------------------------------------------------------
    // 启动
    // ---------------------------------------------------------------------------------------------

    private fun startInternal() {
        if (disposed.get()) return
        if (status.get().state == DshState.RUNNING || status.get().state == DshState.STARTING) return

        val settings = DshSettings.getInstance().current
        publish(DshStatus(DshState.STARTING, port = settings.port))
        val startedAt = System.nanoTime()
        fun mark(step: String) {
            log.info("[启动耗时] $step: ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }

        val profile = settings.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
        // profile 默认是指向桌面端 `desktop` 的目录链接，两者共享同一份配置。
        runCatching { ProfileManager.ensureLinked(profile) }
            .onSuccess { result ->
                if (result.ok) {
                    log.info("profile 就绪: " + result.steps.joinToString("；"))
                } else {
                    log.warn("profile 链接检查未通过：${result.error ?: result.steps.joinToString("；")}")
                }
            }
            .onFailure { log.warn("确保 profile 链接失败（继续启动）", it) }
        val profileDir = DshProfileBootstrap.ensure(profile) ?: run {
            publish(DshStatus(DshState.FAILED, detail = "无法创建 profile: $profile"))
            return
        }
        // ideWorkspace、在 IDE 打开文件、右键插入引用等能力由 DSH 侧插件 `dsh-plugin-jetbrains`
        // 提供；这里把插件注入 profile，并用 `--patch` 覆盖层挂载。
        val overlay = DshBundleBootstrap.install(profileDir)
        if (overlay == null) {
            log.warn("DSH 侧插件注入失败，ideWorkspace/文件桥可能不可用（profile=$profile）")
        } else {
            log.info("DSH 侧插件: --patch $overlay")
        }
        mark("profile 与插件注入完成")

        // 依赖官方桌面客户端：复用其安装目录里携带的运行时（Electron 以 node 模式运行内嵌 CLI）。
        // 未安装客户端时自动按系统下载安装包并安装（下载较大，放在后台 lifecycle 线程）。
        val client = if (DshCommandResolver.isAutoConfigured(settings.dshCommand)) {
            val ensured = runCatching {
                DshClientProvisioner.ensure { message ->
                    publish(DshStatus(DshState.STARTING, port = settings.port, detail = message))
                }
            }.onFailure { error ->
                log.warn("准备官方客户端失败", error)
                publish(DshStatus(DshState.FAILED, detail = "准备客户端失败: ${error.message ?: error}"))
            }.getOrNull() ?: return
            log.info(
                "DSH 客户端就绪: ${ensured.installDir}（版本 ${ensured.version ?: "?"}，Node ${ensured.nodeVersion ?: "?"}）",
            )
            ensured
        } else {
            null
        }
        mark("客户端就绪")

        val port = resolveFreePort(settings.port)
        if (port != settings.port) {
            // 端口变化会改变 origin（localStorage 会丢），所以记日志并写回设置，
            // 让后续启动尽量稳定在同一个端口上。
            log.info("DSH 端口 ${settings.port} 被占用，改用 $port")
            settings.port = port
        }

        val tokens = buildCommand(settings, profile, port, overlay, client) ?: return
        val pb = ProcessBuilder(tokens)
        // 不设置 DSH_HOME：沿用 DSH 自身解析（$DSH_HOME 或 ~/.dsh），与桌面端共享数据。
        // cwd 用 home 之外的稳定目录，避免继承 IDE 的任意工作目录。
        pb.directory(File(System.getProperty("user.home") ?: "."))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        // 首次启动的 DSH 语言/主题：DSH 侧插件读到这两个变量且没有初始化标记时，
        // 调用官方 configEditor 写入 profile 用户层（不覆盖用户已显式设置的值）。
        env["DSH_IDE_LOCALE"] = DshProfileAppearance.detectIdeLanguage()
        env["DSH_IDE_THEME"] = DshProfileAppearance.detectIdeTheme()
        if (client != null) {
            // 用客户端自带的 Electron 以 node 模式运行内嵌 CLI；并把它自带的 Node 启动器目录
            // 放到 PATH 最前面，保证 dsh 派生的子进程（工具、MCP 等）用同一个 Node。
            env["ELECTRON_RUN_AS_NODE"] = "1"
            env["DSH_DESKTOP_NODE_EXECUTABLE"] = client.executable.toString()
            client.runtimeBinDir()?.let { bin -> prependToPath(env, bin) }
        }

        log.info("启动 DSH: ${tokens.joinToString(" ")}")
        val spawned = try {
            pb.start()
        } catch (e: Exception) {
            publish(DshStatus(DshState.FAILED, detail = "启动失败: ${e.message}"))
            return
        }
        process = spawned
        stopRequested = false
        startExitWatchdog(spawned.pid())
        mark("宿主进程已启动")

        // 重启时清掉上一轮可能残留的 URL
        val url = awaitUrl(spawned, 120_000)
        if (url == null) {
            val detail = if (spawned.isAlive) "等待 URL 超时" else "进程提前退出（code=${spawned.exitValue()}）"
            killQuietly(spawned)
            process = null
            publish(DshStatus(DshState.FAILED, detail = detail))
            return
        }
        mark("收到 Web URL")

        if (!awaitHealthy(url, 45_000)) {
            killQuietly(spawned)
            process = null
            publish(DshStatus(DshState.FAILED, detail = "健康检查失败"))
            return
        }
        mark("健康检查通过")

        publish(DshStatus(DshState.RUNNING, url = url, port = port))
        watch(spawned)
    }

    /**
     * 组装启动命令。解析失败时发布 FAILED 并返回 null，调用方必须据此终止本次启动：
     * 命令不存在时若照样起进程，用户只会看到「进程提前退出」，看不出是命令没找到。
     *
     * @param client 官方客户端安装信息；非空时直接 `Electron --expose-internals <cli.js>`。
     *               为 null 表示用户显式配置了外部 dsh 命令，走 [DshCommandResolver]。
     */
    private fun buildCommand(
        settings: DshSettings.Settings,
        profile: String,
        port: Int,
        dshPluginPatch: java.nio.file.Path?,
        client: DshClientInstall?,
    ): List<String>? {
        val base = if (client != null) {
            log.info("DSH 命令来源: 官方客户端 ${client.installDir}")
            client.commandPrefix()
        } else {
            // 外部覆盖：解析职责归 DshCommandResolver（显式路径 → PATH）。
            // resolver 会读文件系统，PATH 里可能存在非法路径（InvalidPathException）等运行时异常；
            // 异常一旦漏到 lifecycle 线程会被 ScheduledFuture 静默吞掉，状态永远停在 STARTING，
            // 而 startInternal 开头的 STARTING 判断会挡住重试。所以这里必须兜底成 FAILED。
            val resolution = runCatching {
                DshCommandResolver.resolve(settings.dshCommand, System.getenv("PATH"))
            }.onFailure { error ->
                log.warn("解析 dsh 命令失败", error)
                publish(DshStatus(DshState.FAILED, detail = "解析 dsh 命令失败: ${error.message}"))
            }.getOrNull() ?: return null
            when (resolution) {
                is DshCommandResolution.Resolved -> {
                    log.info("DSH 命令来源: ${resolution.source}")
                    resolution.command
                }

                is DshCommandResolution.Missing -> {
                    log.warn(resolution.message)
                    publish(DshStatus(DshState.FAILED, detail = resolution.message))
                    return null
                }
            }
        }
        // 注意：自定义 profile 不能用 `web` 子命令（`web` 是 `--profile web` 的语法糖，
        // 两者同时出现会被解析成多余的位置参数）。launcher 旗标（--profile/--patch）
        // 必须在应用参数（--host/--port/--no-open）之前，launcher 遇到不认识的参数就停。
        val args = base + listOf("--profile", profile) +
            (if (dshPluginPatch != null) listOf("--patch", dshPluginPatch.toString()) else emptyList()) +
            listOf(
                "--host", settings.host.ifBlank { "127.0.0.1" },
                "--port", port.toString(),
                "--no-open",
            )
        return if (needsWindowsShell(base.first())) listOf("cmd.exe", "/c") + args else args
    }

    /**
     * Windows 上只有 `.cmd` / `.bat` / 无扩展名的启动器需要经 `cmd.exe /c`；
     * 官方客户端直接启动 `DeepSeek Harness.exe`，不需要 cmd（避免含空格路径的转义问题）。
     */
    private fun needsWindowsShell(executable: String): Boolean {
        if (!SystemInfo.isWindows) return false
        val lower = executable.lowercase()
        return !lower.endsWith(".exe")
    }

    /** 从首选端口开始向上探测，最多 +50；都不可用则回退操作系统分配的端口。 */
    private fun resolveFreePort(preferred: Int): Int {
        // 非法首选端口（0 / 越界）不能直接拿去做 bind：isFree(0) 会绑到临时端口并"成功"，
        // 结果把 0 当成可用端口传下去，违反「端口不能为 0」的不变量。回退到默认端口探测。
        val start = if (preferred in 1..65535) preferred else DshSettings.DEFAULT_PORT
        for (candidate in start..(start + 50)) {
            if (isFree(candidate)) return candidate
        }
        return ServerSocket(0).use { it.localPort }
    }

    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    /**
     * 读 stdout 直到出现 `dsh web: http://127.0.0.1:<port>/?token=...`。
     * 用阻塞队列把 URL 立即交给启动线程，避免轮询间隔带来的额外等待。
     */
    private fun awaitUrl(process: Process, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val found = java.util.concurrent.LinkedBlockingQueue<String>()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val pattern = Regex("""https?://[0-9A-Za-z.\-]+:\d+(?:/[^\s]*)?""")
        val thread = Thread {
            runCatching {
                reader.lineSequence().forEach { line ->
                    log.info("[dsh] $line")
                    pattern.find(line)?.value?.let(found::offer)
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        while (System.nanoTime() < deadline) {
            found.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
            if (!process.isAlive) return null
        }
        return null
    }

    private fun awaitHealthy(url: String, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val probe = runCatching { URI.create(url) }.getOrNull() ?: return false
        while (System.nanoTime() < deadline) {
            val ok = runCatching {
                val conn = probe.toURL().openConnection() as HttpURLConnection
                // 带 token 的 GET 会返回 303 到 `./` 并下发 cookie。默认跟随重定向会落到
                // 没有凭据的根路径（401），健康检查因此永远失败——这里必须自己看 3xx。
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                code in 200..399
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun watch(process: Process) {
        // 只挂一个退出回调：非主动停止的退出视为异常，发布 FAILED 由 UI 呈现。
        process.onExit().thenRun {
            if (stopRequested || disposed.get()) return@thenRun
            publish(
                DshStatus(
                    DshState.FAILED,
                    detail = "进程意外退出（code=${runCatching { process.exitValue() }.getOrDefault(-1)}）",
                ),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 停止
    // ---------------------------------------------------------------------------------------------

    private fun stopInternal() {
        stopRequested = true
        killWatchdog()
        val current = process ?: run {
            publish(DshStatus(DshState.STOPPED))
            return
        }
        killQuietly(current)
        process = null
        publish(DshStatus(DshState.STOPPED))
    }

    /**
     * Windows 上启一个隐藏守护进程：`Wait-Process -Id <IDE进程>` 阻塞到 IDE 进程消失，然后强杀 dsh 进程树。
     * 覆盖 IDE 被强制结束（任务管理器 / 崩溃）时 `dispose()` 不执行、dsh 变成孤儿的情况。
     * 正常关闭时 [stopInternal] 会先 [killWatchdog]，由 dispose 自己杀 dsh。
     */
    private fun startExitWatchdog(childPid: Long) {
        if (!SystemInfo.isWindows) return
        runCatching {
            killWatchdog()
            val idePid = ProcessHandle.current().pid()
            val script = "Wait-Process -Id $idePid -ErrorAction SilentlyContinue; " +
                "taskkill /T /F /PID $childPid 2>\$null"
            watchdog = ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script,
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { log.warn("启动 IDE 退出守护失败（IDE 被强杀时 dsh 可能残留）", it) }
    }

    private fun killWatchdog() {
        runCatching { watchdog?.destroyForcibly() }
        watchdog = null
    }

    private fun killQuietly(target: Process) {
        runCatching {
            if (SystemInfo.isWindows) {
                // 与 zcode-ide 一致：dsh 会派生子进程（工具、pty），必须杀整棵树。
                ProcessBuilder("taskkill", "/T", "/F", "/PID", target.pid().toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS)
            } else {
                target.destroy()
                if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly()
            }
        }.onFailure { log.warn("停止 DSH 进程失败", it) }
    }

    private fun publish(next: DshStatus) {
        status.set(next)
        listeners.forEach { runCatching { it(next) } }
    }

    override fun dispose() {
        disposed.set(true)
        stopInternal()
        lifecycle.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun getInstance(): DshServerService =
            ApplicationManager.getApplication().getService(DshServerService::class.java)
    }
}

/**
 * 把 [dir] 插到 PATH 最前面。
 *
 * Windows 进程的环境变量块沿用创建者写入的键名大小写（资源管理器启动的进程通常是
 * `Path`），而 [ProcessBuilder.environment] 返回的是大小写敏感的 Map：直接写
 * `env["PATH"]` 会新造一个键，同时把原 PATH 读成 null——结果子进程的 PATH 只剩
 * [dir]，`explorer` / `git` / `npm` 等一律 ENOENT（插件市场「在文件夹中显示」抛
 * `spawn explorer ENOENT`、宿主随之崩溃即由此引起）。所以必须按键名不区分大小写查找。
 */
internal fun prependToPath(env: MutableMap<String, String>, dir: java.nio.file.Path) {
    val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    env[key] = dir.toString() + File.pathSeparator + env[key].orEmpty()
}
