package dev.doyzheng.dsh.ide.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 已安装的 IDEA 插件（`plugins/dsh-idea-plugin/lib/dsh-idea-plugin-<version>.jar`）。 */
data class InstalledPlugin(
    val installed: Boolean,
    val pluginDir: String? = null,
    val jar: String? = null,
    val version: String? = null,
)

/** 本机一个 JetBrains IDE 安装。 */
data class JetBrainsIde(
    val id: String,
    val name: String,
    val version: String?,
    val buildNumber: String?,
    val productCode: String?,
    val dataDirectoryName: String?,
    val installPath: String,
    val exePath: String,
    val configDir: String,
    val pluginsDir: String,
    val running: Boolean,
    val installed: InstalledPlugin,
)

/**
 * 检测本机安装的 JetBrains IDE。
 *
 * 与旧 JS 实现一致：读取各安装目录的 `product-info.json`（不靠注册表猜测），
 * 覆盖独立安装（Program Files）、`%LOCALAPPDATA%\\Programs`、JetBrains Toolbox 布局。
 * 运行状态用 Java 的 [ProcessHandle] 判定，不依赖 PowerShell。
 */
object JetBrainsDetector {

    const val IDE_PLUGIN_DIR_NAME = "dsh-idea-plugin"

    private val KNOWN_LAUNCHERS = listOf(
        "idea64.exe", "webstorm64.exe", "pycharm64.exe", "goland64.exe", "phpstorm64.exe",
        "rider64.exe", "clion64.exe", "datagrip64.exe", "rubymine64.exe", "rustrover64.exe",
        "aqua64.exe", "fleet.exe", "studio64.exe",
    )

    fun detect(env: Map<String, String> = System.getenv()): List<JetBrainsIde> {
        val candidates = linkedSetOf<Path>()
        for (root in roots(env)) {
            val entries = runCatching { Files.newDirectoryStream(root).use { it.toList() } }.getOrNull() ?: continue
            for (entry in entries) {
                if (!Files.isDirectory(entry)) continue
                candidates.add(entry)
                // Toolbox：apps/<product>/<channel>/<build>
                if (root.toString().replace('\\', '/').endsWith("/Toolbox/apps")) {
                    for (channel in listDirs(entry)) {
                        candidates.add(channel)
                        for (build in listDirs(channel)) candidates.add(build)
                    }
                }
            }
        }
        val found = mutableListOf<JetBrainsIde>()
        val seen = mutableSetOf<String>()
        for (dir in candidates) {
            val raw = probe(dir, env) ?: continue
            val key = Paths.get(raw.installPath).toAbsolutePath().normalize().toString().lowercase()
            if (!seen.add(key)) continue
            val configDir = configDirOf(raw.dataDirectoryName, raw.name, env)
            val pluginsDir = configDir.resolve("plugins")
            found += JetBrainsIde(
                id = raw.installPath,
                name = raw.name,
                version = raw.version,
                buildNumber = raw.buildNumber,
                productCode = raw.productCode,
                dataDirectoryName = raw.dataDirectoryName,
                installPath = raw.installPath,
                exePath = raw.exePath,
                configDir = configDir.toString(),
                pluginsDir = pluginsDir.toString(),
                running = false,
                installed = installedPluginInfo(pluginsDir),
            )
        }
        // 一次遍历进程，回填 running。
        val processes = listProcesses()
        val result = found.map { ide ->
            ide.copy(running = processes.any { belongsTo(it, ide) })
        }
        return result.sortedWith(compareByDescending<JetBrainsIde> { it.running }.thenBy { it.name.lowercase() })
    }

    private data class Raw(
        val name: String,
        val version: String?,
        val buildNumber: String?,
        val productCode: String?,
        val dataDirectoryName: String?,
        val installPath: String,
        val exePath: String,
    )

    private fun roots(env: Map<String, String>): List<Path> {
        val home = env["USERPROFILE"] ?: System.getProperty("user.home") ?: "."
        val roots = mutableListOf<Path>()
        if (SystemInfo.isWindows) {
            val pf = env["ProgramFiles"] ?: "C:\\Program Files"
            val pf86 = env["ProgramFiles(x86)"] ?: "C:\\Program Files (x86)"
            val local = env["LOCALAPPDATA"] ?: Paths.get(home, "AppData", "Local").toString()
            roots.add(Paths.get(pf, "JetBrains"))
            roots.add(Paths.get(pf86, "JetBrains"))
            roots.add(Paths.get(local, "Programs"))
            roots.add(Paths.get(local, "JetBrains", "Toolbox", "apps"))
        } else if (SystemInfo.isMac) {
            roots.add(Paths.get("/Applications"))
            roots.add(Paths.get(home, "Applications"))
        } else {
            roots.add(Paths.get("/opt"))
            roots.add(Paths.get(home, ".local", "share", "JetBrains", "Toolbox", "apps"))
        }
        return roots.filter { Files.isDirectory(it) }
    }

    private fun listDirs(dir: Path): List<Path> =
        runCatching { Files.newDirectoryStream(dir).use { s -> s.filter { Files.isDirectory(it) } } }.getOrDefault(emptyList())

    private fun probe(dir: Path, env: Map<String, String>): Raw? {
        val info = readJsonObject(dir.resolve("product-info.json")) ?: return null
        val name = info.str("name") ?: dir.fileName?.toString() ?: return null
        val launches = info.getAsJsonArray("launch")
        var rel: String? = null
        if (launches != null) {
            for (element in launches) {
                val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
                val os = obj.str("os")
                val launcher = obj.str("launcherPath")
                if (launcher != null && (os == null || os == "Windows")) {
                    rel = launcher
                    if (os == "Windows") break
                }
            }
        }
        var exe = rel?.let { runCatching { dir.resolve(it).toAbsolutePath().normalize().toString() }.getOrNull() }
        if (exe == null || !Files.isRegularFile(Paths.get(exe))) {
            exe = KNOWN_LAUNCHERS.map { dir.resolve("bin").resolve(it) }.firstOrNull { Fs.exists(it) }?.toString()
        }
        if (exe == null || !Files.isRegularFile(Paths.get(exe))) return null
        return Raw(
            name = name,
            version = info.str("version"),
            buildNumber = info.str("buildNumber"),
            productCode = info.str("productCode"),
            dataDirectoryName = info.str("dataDirectoryName"),
            installPath = dir.toAbsolutePath().normalize().toString(),
            exePath = exe,
        )
    }

    private fun configDirOf(dataDirectoryName: String?, name: String, env: Map<String, String>): Path {
        val dataDir = dataDirectoryName ?: name.replace("\\s+".toRegex(), "")
        val home = env["USERPROFILE"] ?: System.getProperty("user.home") ?: "."
        return when {
            SystemInfo.isWindows -> {
                val appData = env["APPDATA"] ?: Paths.get(home, "AppData", "Roaming").toString()
                Paths.get(appData, "JetBrains", dataDir)
            }
            SystemInfo.isMac -> Paths.get(home, "Library", "Application Support", "JetBrains", dataDir)
            else -> Paths.get(env["XDG_CONFIG_HOME"] ?: Paths.get(home, ".config").toString(), "JetBrains", dataDir)
        }
    }

    /** 读取已安装的 dsh-idea-plugin 版本。 */
    fun installedPluginInfo(pluginsDir: Path): InstalledPlugin {
        val dir = pluginsDir.resolve(IDE_PLUGIN_DIR_NAME)
        if (!Fs.exists(dir)) return InstalledPlugin(installed = false)
        val jars = runCatching {
            Files.newDirectoryStream(dir.resolve("lib")).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.toList() }
        }.getOrDefault(emptyList())
        val jar = jars.firstOrNull()
        val version = jar?.fileName?.toString()?.let { name ->
            Regex("^dsh-idea-plugin-(.+)\\.jar$").find(name)?.groupValues?.getOrNull(1)
        }
        return InstalledPlugin(installed = true, pluginDir = dir.toString(), jar = jar?.toString(), version = version)
    }

    // -----------------------------------------------------------------------------------------
    // 进程
    // -----------------------------------------------------------------------------------------

    data class Proc(val pid: Long, val path: String)

    fun listProcesses(): List<Proc> = runCatching {
        ProcessHandle.allProcesses().toList().mapNotNull { handle ->
            val command = runCatching { handle.info().command().orElse("") }.getOrDefault("")
            if (command.isBlank()) null else Proc(handle.pid(), command)
        }
    }.getOrDefault(emptyList())

    private fun belongsTo(proc: Proc, ide: JetBrainsIde): Boolean {
        if (proc.path.isBlank()) return false
        val p = proc.path.replace('/', '\\').lowercase()
        val exe = ide.exePath.replace('/', '\\').lowercase()
        if (p == exe) return true
        val install = ide.installPath.replace('/', '\\').lowercase().trimEnd('\\')
        return p.startsWith("$install\\")
    }

    fun runningProcesses(ide: JetBrainsIde): List<ProcessHandle> {
        val install = ide.installPath.replace('/', '\\').lowercase().trimEnd('\\')
        val exe = ide.exePath.replace('/', '\\').lowercase()
        return runCatching {
            ProcessHandle.allProcesses().toList().filter { handle ->
                val cmd = runCatching { handle.info().command().orElse("") }.getOrDefault("")
                if (cmd.isBlank()) return@filter false
                val p = cmd.replace('/', '\\').lowercase()
                p == exe || p.startsWith("$install\\")
            }
        }.getOrDefault(emptyList())
    }

    // -----------------------------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------------------------

    private fun readJsonObject(path: Path): JsonObject? {
        val text = Fs.readText(path) ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = runCatching {
        val element = get(key) ?: return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }.getOrNull()
}
