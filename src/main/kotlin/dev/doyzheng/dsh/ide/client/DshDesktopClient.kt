package dev.doyzheng.dsh.ide.client

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import dev.doyzheng.dsh.ide.jetbrains.Fs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 本机安装的官方 **DeepSeek Harness 桌面客户端**。
 *
 * 复用桌面客户端安装目录里携带的运行时：
 * 直接用客户端自带的 Electron 二进制以 `ELECTRON_RUN_AS_NODE=1` 运行桌面端内嵌的
 * `dsh-desktop-host/lib/cli.js`，与官方 `dsh.cmd` 的行为一致，但绕开了 `cmd.exe` 与 PATH。
 *
 * 布局（Windows / macOS 通用，仅 Resources 根不同）：
 *  - Windows：`<安装目录>/DeepSeek Harness.exe` + `<安装目录>/resources`
 *  - macOS：`<App.app>/Contents/MacOS/DeepSeek Harness` + `<App.app>/Contents/Resources`
 *  - 两者内部都是 `app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js`
 *    （`app.asar` 是单文件归档，cli.js 在归档内，磁盘上不能用 `Files.isRegularFile` 判断，
 *    只能判断 `app.asar` 本身存在）。
 */
data class DshClientInstall(
    val installDir: Path,
    /** Electron 可执行文件（以 node 模式运行）。 */
    val executable: Path,
    /** 桌面端内嵌的 dsh CLI 入口（位于 app.asar 内，仅 Electron 可解析）。 */
    val cliEntry: Path,
    /** `resources` / `Contents/Resources` 目录。 */
    val resourcesDir: Path,
    /** 客户端版本（优先 runtime.json 的 desktopVersion，其次注册表 DisplayVersion）。 */
    val version: String?,
    /** 随客户端携带的 Node 版本（runtime.json）。 */
    val nodeVersion: String?,
    /** 命中来源，便于日志定位。 */
    val source: String,
) {
    /**
     * 启动 dsh 的命令前缀，**不含** `--profile/--host/--port` 等应用参数。
     * 形如 `["…/DeepSeek Harness.exe", "--expose-internals", "…/cli.js"]`。
     */
    fun commandPrefix(): List<String> =
        listOf(executable.toString(), "--expose-internals", cliEntry.toString())

    /** 客户端自带的 node 启动器目录；用于给 dsh 派生出的子进程提供同一个 Node。 */
    fun runtimeBinDir(): Path? =
        resourcesDir.resolve("runtime").resolve("bin").takeIf { Files.isDirectory(it) }
}

/** 一条 Windows 卸载注册表项里我们关心的字段。 */
data class RegistryApp(val displayName: String, val installLocation: String?, val displayVersion: String?)

/**
 * 定位本机已安装的官方客户端。
 *
 * 纯文件系统 + `reg query` 读取，不启动客户端、不写文件。命中顺序：
 *  1. 注册表卸载项里的 `InstallLocation`（能覆盖自定义安装目录，如 `D:\apps\dsh`）；
 *  2. 常见默认安装目录（`%LOCALAPPDATA%\Programs\…`、`Program Files`、`/Applications` 等）。
 *
 * `detect` 把候选目录与注册表项作为参数显式传入，单测不依赖本机是否真的装了客户端。
 */
object DshDesktopClient {

    private val PRODUCT_PATTERN = Regex("""deepseek[\s_-]*harness""", RegexOption.IGNORE_CASE)

    /**
     * 定位已安装客户端；未安装返回 null。
     *
     * 先查默认安装目录，命中就不扫注册表：`reg query /s` 要遍历整个 Uninstall 子树，
     * 每次启动多花几百毫秒。只有默认目录都没有时才回退到注册表（自定义安装目录）。
     */
    fun locate(): DshClientInstall? {
        detect(candidateInstallDirs(), emptyList())?.let { return it }
        val registry = readRegistryApps()
        if (registry.isEmpty()) return null
        return detect(emptyList(), registry)
    }

    internal fun detect(candidates: List<Path>, registry: List<RegistryApp>): DshClientInstall? {
        val versionByDir = HashMap<Path, String?>()
        val ordered = LinkedHashSet<Path>()
        for (app in registry) {
            val raw = app.installLocation?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val path = runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrNull() ?: continue
            ordered.add(path)
            versionByDir[path] = app.displayVersion
        }
        candidates.forEach { dir ->
            val normalized = runCatching { dir.toAbsolutePath().normalize() }.getOrNull() ?: return@forEach
            ordered.add(normalized)
        }
        for (dir in ordered) {
            fromInstallDir(dir, versionByDir[dir], source = if (versionByDir.containsKey(dir)) "registry" else "path")
                ?.let { return it }
        }
        return null
    }

    /** 从一个安装目录构造 [DshClientInstall]；目录不是有效客户端返回 null。 */
    internal fun fromInstallDir(dir: Path, versionOverride: String? = null, source: String = "path"): DshClientInstall? {
        val layout = layoutOf(dir) ?: return null
        val (executable, resourcesDir) = layout
        val info = readRuntimeInfo(resourcesDir)
        return DshClientInstall(
            installDir = runCatching { dir.toAbsolutePath().normalize() }.getOrDefault(dir),
            executable = executable,
            cliEntry = resourcesDir.resolve("app.asar").resolve("dsh").resolve("node_modules")
                .resolve("@deepseek-ai").resolve("dsh-desktop-host").resolve("lib").resolve("cli.js"),
            resourcesDir = resourcesDir,
            version = info.first ?: versionOverride,
            nodeVersion = info.second,
            source = source,
        )
    }

    /** 尝试两种安装布局，返回 (可执行文件, resources 目录)。 */
    internal fun layoutOf(dir: Path): Pair<Path, Path>? {
        val winExe = dir.resolve("DeepSeek Harness.exe")
        val winResources = dir.resolve("resources")
        if (Files.isRegularFile(winExe) && Files.exists(winResources.resolve("app.asar"))) {
            return winExe to winResources
        }
        val macExe = dir.resolve("Contents").resolve("MacOS").resolve("DeepSeek Harness")
        val macResources = dir.resolve("Contents").resolve("Resources")
        if (Files.isRegularFile(macExe) && Files.exists(macResources.resolve("app.asar"))) {
            return macExe to macResources
        }
        return null
    }

    /** 读取 `<resources>/runtime/primary-runtime/runtime.json` 的 desktopVersion / node。 */
    internal fun readRuntimeInfo(resourcesDir: Path): Pair<String?, String?> {
        val file = resourcesDir.resolve("runtime").resolve("primary-runtime").resolve("runtime.json")
        val text = Fs.readText(file) ?: return null to null
        return runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            fun value(key: String): String? =
                json.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            value("desktopVersion") to value("node")
        }.getOrDefault(null to null)
    }

    // ---------------------------------------------------------------------------------------------
    // 候选目录
    // ---------------------------------------------------------------------------------------------

    internal fun candidateInstallDirs(env: Map<String, String> = System.getenv()): List<Path> {
        val home = env["USERPROFILE"] ?: System.getProperty("user.home") ?: "."
        val dirs = mutableListOf<Path>()
        if (SystemInfo.isWindows) {
            val local = env["LOCALAPPDATA"] ?: Paths.get(home, "AppData", "Local").toString()
            val pf = env["ProgramFiles"] ?: "C:\\Program Files"
            val pf86 = env["ProgramFiles(x86)"] ?: "C:\\Program Files (x86)"
            dirs.add(Paths.get(local, "Programs", "DeepSeek Harness"))
            dirs.add(Paths.get(local, "Programs", "deepseek-harness"))
            dirs.add(Paths.get(local, "DeepSeek Harness"))
            dirs.add(Paths.get(pf, "DeepSeek Harness"))
            dirs.add(Paths.get(pf86, "DeepSeek Harness"))
        } else if (SystemInfo.isMac) {
            dirs.add(Paths.get("/Applications", "DeepSeek Harness.app"))
            dirs.add(Paths.get(home, "Applications", "DeepSeek Harness.app"))
        }
        return dirs
    }

    // ---------------------------------------------------------------------------------------------
    // 注册表
    // ---------------------------------------------------------------------------------------------

    private fun readRegistryApps(): List<RegistryApp> {
        if (!SystemInfo.isWindows) return emptyList()
        val roots = listOf(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        )
        val apps = mutableListOf<RegistryApp>()
        for (root in roots) {
            val result = Fs.run(listOf("reg", "query", root, "/s"), timeoutSeconds = 20)
            if (result.code != 0 || result.output.isBlank()) continue
            apps += parseRegQuery(result.output).filter { isProduct(it.displayName) }
        }
        return apps
    }

    internal fun isProduct(displayName: String?): Boolean =
        displayName != null && PRODUCT_PATTERN.containsMatchIn(displayName)

    /**
     * 解析 `reg query <root> /s` 的输出。输出按注册表键分块：
     * 键名行不以空白开头（`HKEY_…`），值行以空白开头、字段间用多个空格分隔。
     */
    internal fun parseRegQuery(output: String): List<RegistryApp> {
        val blocks = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            if (!line.first().isWhitespace()) {
                if (line.startsWith("HKEY", ignoreCase = true)) {
                    current = mutableMapOf()
                    blocks.add(current)
                }
                continue
            }
            val match = Regex("""^\s+(.+?)\s{2,}(REG_[A-Z_]+)\s{2,}(.*)$""").find(line) ?: continue
            current?.put(match.groupValues[1].trim(), match.groupValues[3].trim())
        }
        return blocks.mapNotNull { block ->
            val name = block["DisplayName"] ?: return@mapNotNull null
            RegistryApp(
                displayName = name,
                installLocation = block["InstallLocation"]?.takeIf { it.isNotBlank() },
                displayVersion = block["DisplayVersion"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
