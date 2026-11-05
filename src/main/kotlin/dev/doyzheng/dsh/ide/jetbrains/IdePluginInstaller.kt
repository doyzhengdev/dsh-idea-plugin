package dev.doyzheng.dsh.ide.jetbrains

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import dev.doyzheng.dsh.ide.DshProfileBootstrap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 安装/卸载结果：`steps` 是给用户看的操作日志，`error` 非空表示失败。 */
data class InstallResult(
    val ok: Boolean,
    val steps: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * IDE 插件的安装 / 卸载 / 启停。
 *
 * 安装源：**当前正在运行的这份插件自己的目录**（plugins/<dir>/lib 下的 jar），
 * 因为「以 IDEA 插件为主发布」后，插件本体就是权威产物，不需要额外打包 zip。
 * 目标：本机检测到的其它 JetBrains IDE 的 `plugins/dsh-idea-plugin`。
 *
 * 破坏性动作前一律备份到 `~/.dsh/dsh-ide/backups/`。
 */
object IdePluginInstaller {

    private val LEGACY_PLUGIN_DIR_NAMES = listOf("jet-brains-plugin-dsh", "dsh-idea", "dsh-ide")

    private fun backupRoot(): Path = DshProfileBootstrap.dshHome().resolve("dsh-ide").resolve("backups")

    // -----------------------------------------------------------------------------------------
    // 定位「自己的插件目录」
    // -----------------------------------------------------------------------------------------

    /**
     * 当前插件的安装目录（含 lib 下 jar 的那一层）。
     *
     * 1. 优先用类所在 jar 的路径反推（`.../plugins/<dir>/lib/x.jar` → `.../plugins/<dir>`）；
     * 2. 开发态（classes 目录，不是 jar）回退到 `PathManager.getPluginsPath()` 扫描。
     */
    fun locateSelfPluginDir(): Path? {
        runCatching {
            val location = IdePluginInstaller::class.java.protectionDomain?.codeSource?.location
            if (location != null) {
                val path = Paths.get(location.toURI())
                if (Files.isRegularFile(path)) {
                    val lib = path.parent
                    if (lib != null && lib.fileName?.toString() == "lib") return lib.parent
                }
            }
        }
        val pluginsRoot = runCatching { Paths.get(PathManager.getPluginsPath()) }.getOrNull() ?: return null
        val direct = pluginsRoot.resolve(JetBrainsDetector.IDE_PLUGIN_DIR_NAME)
        if (isPluginDir(direct)) return direct
        // 扫描：目录下含 lib/dsh-idea-plugin-*.jar，或 META-INF/plugin.xml 带我们的 id。
        val entries = runCatching { Files.newDirectoryStream(pluginsRoot).use { it.toList() } }.getOrDefault(emptyList())
        return entries.firstOrNull { isPluginDir(it) }
    }

    private fun isPluginDir(dir: Path): Boolean {
        if (!Files.isDirectory(dir)) return false
        val hasJar = runCatching {
            Files.newDirectoryStream(dir.resolve("lib")).use { s ->
                s.any { it.fileName.toString().matches(Regex("^dsh-idea-plugin-.+\\.jar$")) }
            }
        }.getOrDefault(false)
        if (hasJar) return true
        val xml = Fs.readText(dir.resolve("META-INF").resolve("plugin.xml"))
        if (xml != null && xml.contains("dev.doyzheng.dsh-ide")) return true
        // 已安装的插件：plugin.xml 在 jar 内，用目录名兜底。
        return dir.fileName?.toString() == JetBrainsDetector.IDE_PLUGIN_DIR_NAME
    }

    // -----------------------------------------------------------------------------------------
    // 安装 / 卸载
    // -----------------------------------------------------------------------------------------

    fun install(
        ide: JetBrainsIde,
        stopRunning: Boolean = true,
        startAfter: Boolean = false,
        force: Boolean = true,
        projectPath: String? = null,
        onStep: (String) -> Unit = {},
    ): InstallResult {
        val steps = mutableListOf<String>()
        fun step(s: String) { steps += s; onStep(s) }

        val source = locateSelfPluginDir()
            ?: return InstallResult(false, steps, "无法定位当前插件目录（开发态请在已安装插件中运行）")

        val targetDir = Paths.get(ide.pluginsDir, JetBrainsDetector.IDE_PLUGIN_DIR_NAME)
        if (samePath(source, targetDir)) {
            return InstallResult(false, steps, "目标是当前正在使用的这个 IDE：请用 Settings → Plugins 从磁盘安装/更新后重启")
        }
        step("插件源：$source")

        val running = JetBrainsDetector.runningProcesses(ide)
        if (running.isNotEmpty()) {
            if (!stopRunning) return InstallResult(false, steps, "IDE 正在运行，请先关闭或勾选「自动关闭 IDE」")
            val stopped = stopIde(ide, force)
            stopped.steps.forEach { step(it) }
            if (!stopped.ok) return InstallResult(false, steps, "无法关闭 IDE（仍有进程在运行），请手动关闭后重试")
        }

        val pluginsDir = Paths.get(ide.pluginsDir)
        Files.createDirectories(pluginsDir)

        // 清理改名前的旧插件目录（备份后删除），避免 IDE 同时加载两个插件。
        for (legacy in LEGACY_PLUGIN_DIR_NAMES) {
            val legacyDir = pluginsDir.resolve(legacy)
            if (!Fs.exists(legacyDir)) continue
            val backup = backupRoot().resolve("legacy-$legacy-${Fs.stamp()}")
            Fs.copyTree(legacyDir, backup)
            Fs.deleteRecursively(legacyDir)
            step("已清理旧名插件目录 $legacy（备份到 $backup）")
        }

        if (Fs.exists(targetDir)) {
            val backup = backupRoot().resolve("ide-plugin-${Fs.stamp()}")
            Fs.copyTree(targetDir, backup)
            step("已备份旧插件到 $backup")
            Fs.deleteRecursively(targetDir)
        }

        try {
            Fs.copyTree(source, targetDir)
        } catch (e: Exception) {
            return InstallResult(false, steps, "复制插件目录失败：${e.message}")
        }
        step("已安装到 $targetDir")

        val installed = JetBrainsDetector.installedPluginInfo(pluginsDir)
        if (!installed.installed) return InstallResult(false, steps, "安装后未找到插件目录，请检查 plugins 目录权限")
        step("已就绪：版本 ${installed.version ?: "?"}")

        if (startAfter) {
            val started = startIde(ide, projectPath)
            started.steps.forEach { step(it) }
        }
        return InstallResult(true, steps)
    }

    fun uninstall(
        ide: JetBrainsIde,
        stopRunning: Boolean = true,
        force: Boolean = true,
        onStep: (String) -> Unit = {},
    ): InstallResult {
        val steps = mutableListOf<String>()
        fun step(s: String) { steps += s; onStep(s) }

        val installed = JetBrainsDetector.installedPluginInfo(Paths.get(ide.pluginsDir))
        if (!installed.installed) return InstallResult(true, listOf("未安装 DSH 插件，无需卸载"))

        val running = JetBrainsDetector.runningProcesses(ide)
        if (running.isNotEmpty()) {
            if (!stopRunning) return InstallResult(false, steps, "IDE 正在运行，请先关闭或勾选「自动关闭 IDE」")
            val stopped = stopIde(ide, force)
            stopped.steps.forEach { step(it) }
            if (!stopped.ok) return InstallResult(false, steps, "无法关闭 IDE（仍有进程在运行）")
        }

        val pluginDir = Paths.get(installed.pluginDir!!)
        val backup = backupRoot().resolve("ide-plugin-removed-${Fs.stamp()}")
        Fs.copyTree(pluginDir, backup)
        step("已备份到 $backup")
        Fs.deleteRecursively(pluginDir)
        step("已删除插件目录")
        return InstallResult(true, steps)
    }

    // -----------------------------------------------------------------------------------------
    // 启停
    // -----------------------------------------------------------------------------------------

    fun stopIde(ide: JetBrainsIde, force: Boolean = true, timeoutMs: Long = 30_000): InstallResult {
        val steps = mutableListOf<String>()
        val running = JetBrainsDetector.runningProcesses(ide)
        if (running.isEmpty()) return InstallResult(true, listOf("IDE 未运行"))
        val ids = running.map { it.pid() }
        steps += "请求关闭 ${ide.name}（pid: ${ids.joinToString(", ")}）"
        if (!SystemInfo.isWindows) {
            running.forEach { runCatching { it.destroy() } }
            Thread.sleep(minOf(timeoutMs, 5000))
            if (force) running.forEach { runCatching { it.destroyForcibly() } }
            val remaining = JetBrainsDetector.runningProcesses(ide).map { it.pid() }
            return if (remaining.isEmpty()) InstallResult(true, steps + "IDE 已退出") else InstallResult(false, steps + "IDE 仍在运行")
        }
        val script = """
            ${'$'}ids = @(${ids.joinToString(",")})
            ${'$'}ErrorActionPreference = "SilentlyContinue"
            foreach (${'$'}p in Get-Process -Id ${'$'}ids) { [void]${'$'}p.CloseMainWindow() }
            ${'$'}deadline = (Get-Date).AddMilliseconds($timeoutMs)
            ${'$'}next = (Get-Date).AddSeconds(3)
            while ((Get-Date) -lt ${'$'}deadline) {
              if (@(Get-Process -Id ${'$'}ids -ErrorAction SilentlyContinue).Count -eq 0) { "STOPPED"; exit 0 }
              if ((Get-Date) -ge ${'$'}next) { foreach (${'$'}p in Get-Process -Id ${'$'}ids -ErrorAction SilentlyContinue) { [void]${'$'}p.CloseMainWindow() }; ${'$'}next = (Get-Date).AddSeconds(3) }
              Start-Sleep -Milliseconds 400
            }
            ${if (force) "Get-Process -Id ${'$'}ids -ErrorAction SilentlyContinue | Stop-Process -Force; Start-Sleep -Seconds 2" else ""}
            ${if (force) "if (@(Get-Process -Id ${'$'}ids -ErrorAction SilentlyContinue).Count -eq 0) { \"FORCED\"; exit 0 }" else ""}
            "STILL-RUNNING"; exit 1
        """.trimIndent()
        val output = Fs.powershell(script, timeoutMs / 1000 + 30).output
        val remaining = JetBrainsDetector.runningProcesses(ide).map { it.pid() }
        return if (remaining.isEmpty()) {
            steps += if (output.contains("FORCED")) "等待超时，已强制结束 IDE" else "IDE 已退出"
            InstallResult(true, steps)
        } else {
            steps += "IDE 仍在运行"
            InstallResult(false, steps)
        }
    }

    fun startIde(ide: JetBrainsIde, projectPath: String? = null): InstallResult {
        val steps = mutableListOf<String>()
        val args = mutableListOf(ide.exePath)
        val project = projectPath?.trim().orEmpty()
        if (project.isNotEmpty() && Fs.exists(Paths.get(project))) args += project
        runCatching {
            ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { return InstallResult(false, steps, "启动失败：${it.message}") }
        steps += "已请求启动 ${ide.name}${if (project.isNotEmpty()) "，并打开项目" else ""}"
        return InstallResult(true, steps)
    }

    private fun samePath(a: Path, b: Path): Boolean = runCatching {
        a.toRealPath() == b.toRealPath()
    }.getOrElse {
        a.toAbsolutePath().normalize().toString().equals(b.toAbsolutePath().normalize().toString(), ignoreCase = true)
    }
}
