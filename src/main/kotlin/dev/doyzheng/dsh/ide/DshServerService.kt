package dev.doyzheng.dsh.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
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

        val profile = settings.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
        val profileDir = DshProfileBootstrap.ensure(profile) ?: run {
            publish(DshStatus(DshState.FAILED, detail = "无法创建 profile: $profile"))
            return
        }
        // ideWorkspace 选中项目、点文件在 IDEA 打开、IDE 右键插入引用、设置页等能力，都由真正的
        // DSH 插件 `dsh-plugin-jetbrains` 的 Client 半边提供。无论 profile 是引用/复制/新建，
        // 这里都强制注入该插件：有 bundle 走正常加载，没有则拷贝文件 + `--patch` 覆盖层挂载。
        val bundlePlan = DshBundleBootstrap.install(profileDir)
        if (bundlePlan == null) {
            log.warn("DSH 侧插件注入失败，ideWorkspace/文件桥可能不可用（profile=$profile）")
        } else {
            log.info(
                "DSH 侧插件: " + if (bundlePlan.viaBundle) "由 profile bundles 加载" else "--patch ${bundlePlan.overlay}",
            )
        }
        // 只提示、不阻塞：workspace 列表是整文件重写，同时使用可能互相覆盖。
        // 受限环境里 ProcessHandle.allProcesses() 可能抛异常；检测失败一律按「未运行」处理，
        // 不能让提示逻辑把整个启动流程带进 FAILED。
        if (settings.warnWhenDesktopRunning && runCatching { isDesktopRunning() }.getOrDefault(false)) {
            DshNotifier.notifyDesktopRunningOnce()
        }

        val port = resolveFreePort(settings.port)
        if (port != settings.port) {
            // 端口变化会改变 origin（localStorage 会丢），所以记日志并写回设置，
            // 让后续启动尽量稳定在同一个端口上。
            log.info("DSH 端口 ${settings.port} 被占用，改用 $port")
            settings.port = port
        }

        val tokens = buildCommand(settings, profile, port, bundlePlan?.overlay) ?: return
        val pb = ProcessBuilder(tokens)
        // 不设置 DSH_HOME：沿用 DSH 自身解析（$DSH_HOME 或 ~/.dsh），与桌面端共享数据。
        // cwd 用 home 之外的稳定目录，避免继承 IDE 的任意工作目录。
        pb.directory(File(System.getProperty("user.home") ?: "."))
        pb.redirectErrorStream(true)

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

        // 重启时清掉上一轮可能残留的 URL
        urlSlot.set(null)
        val url = awaitUrl(spawned, 90_000)
        if (url == null) {
            val detail = if (spawned.isAlive) "等待 URL 超时" else "进程提前退出（code=${spawned.exitValue()}）"
            killQuietly(spawned)
            process = null
            publish(DshStatus(DshState.FAILED, detail = detail))
            return
        }

        if (!awaitHealthy(url, 45_000)) {
            killQuietly(spawned)
            process = null
            publish(DshStatus(DshState.FAILED, detail = "健康检查失败"))
            return
        }

        publish(DshStatus(DshState.RUNNING, url = url, port = port))
        watch(spawned)
    }

    /**
     * 组装启动命令。解析失败时发布 FAILED 并返回 null，调用方必须据此终止本次启动：
     * 命令不存在时若照样起进程，用户只会看到「进程提前退出」，看不出是命令没找到。
     */
    private fun buildCommand(
        settings: DshSettings.Settings,
        profile: String,
        port: Int,
        dshPluginPatch: java.nio.file.Path?,
    ): List<String>? {
        // 解析职责归 DshCommandResolver：配置 → PATH → 桌面端 runtime，失败时 message 可操作。
        // resolver 会读文件系统，PATH 里可能存在非法路径（InvalidPathException）等运行时异常；
        // 异常一旦漏到 lifecycle 线程会被 ScheduledFuture 静默吞掉，状态永远停在 STARTING，
        // 而 startInternal 开头的 STARTING 判断会挡住重试。所以这里必须兜底成 FAILED。
        val resolution = runCatching {
            DshCommandResolver.resolve(settings.dshCommand, System.getenv("PATH"))
        }.onFailure { error ->
            log.warn("解析 dsh 命令失败", error)
            publish(DshStatus(DshState.FAILED, detail = "解析 dsh 命令失败: ${error.message}"))
        }.getOrNull() ?: return null
        val base = when (resolution) {
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
        return if (SystemInfo.isWindows) listOf("cmd.exe", "/c") + args else args
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

    /** 读 stdout 直到出现 `dsh web: http://127.0.0.1:<port>/?token=...`。 */
    private fun awaitUrl(process: Process, timeoutMs: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val pattern = Regex("""https?://[0-9A-Za-z.\-]+:\d+(?:/[^\s]*)?""")
        val thread = Thread {
            runCatching {
                reader.lineSequence().forEach { line ->
                    log.info("[dsh] $line")
                    urlSlot.compareAndSet(null, pattern.find(line)?.value)
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        while (System.nanoTime() < deadline) {
            urlSlot.get()?.let { urlSlot.set(null); return it }
            if (!process.isAlive) return null
            Thread.sleep(200)
        }
        return null
    }

    private val urlSlot = AtomicReference<String?>(null)

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

        /**
         * 桌面端是否在运行。两边共享同一个 `$DSH_HOME`，
         * 会话与凭据有锁保护，但 `storages/` 的 workspace 列表是整文件重写（last-write-wins），
         * 同时运行可能出现「项目列表少一条 / 顺序变」的情况，所以启动时提示一次。
         */
        fun isDesktopRunning(): Boolean = ProcessHandle.allProcesses().anyMatch { handle ->
            handle.info().command().orElse("").contains("DeepSeek Harness", ignoreCase = true)
        }
    }
}
