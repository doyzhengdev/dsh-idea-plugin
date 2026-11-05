package dev.doyzheng.dsh.ide

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * dsh 可执行命令的解析结果。
 *
 * 与 `DshServerService` 的契约：`Resolved.command` 只含启动器本身（含用户写的附加参数），
 * 由调用方再追加 `--profile` / `--host` / `--port` 等应用参数。
 */
sealed interface DshCommandResolution {

    /** command 是解析后的可执行命令 tokens（不含 --profile 等应用参数）。 */
    data class Resolved(val command: List<String>, val source: String) : DshCommandResolution

    /** searched 是实际查找过的路径/目录；message 面向用户，必须可操作。 */
    data class Missing(val searched: List<String>, val message: String) : DshCommandResolution
}

/**
 * 把「显式配置 / PATH」解析成可执行的 dsh 命令。
 *
 * 自动模式（空 / `dsh`）由 `DshServerService` 直接使用官方桌面客户端，不走这里；
 * 本对象处理用户显式配置的外部 dsh：显式路径 → PATH。
 *
 * 纯函数：只读文件系统，不读注册表、不写文件、不启动进程。`SystemInfo` 仅作为默认参数，
 * 测试必须显式传 `isWindows` 与 `extraCandidates`，不依赖本机是否装了桌面端。
 *
 * 健壮性约定：所有由用户输入拼接出的 `Path` 都经过 `runCatching`。Windows 上 `| : ? * < >` 等
 * 非法字符会让 `Path.of` / `resolve` 抛 `InvalidPathException`；本对象**从不外抛**，
 * 一律转成 [DshCommandResolution.Missing]，否则调用方（`lifecycle.execute`）会把异常吞掉，
 * 服务状态卡在 STARTING 且无法重试。
 */
object DshCommandResolver {

    private const val SOURCE_CONFIGURED = "配置"
    private const val SOURCE_PATH = "PATH"
    private const val SOURCE_CANDIDATE = "候选路径"

    /** 自动模式：这些写法都等价于「插件自己找 dsh」。 */
    private val AUTO_NAMES = setOf("dsh", "dsh.exe", "dsh.cmd")

    private const val HINT = "请在 Settings > Tools > DSH 设置 dsh 命令；留空或填 dsh 会使用官方 DeepSeek Harness 客户端自带的运行时（未安装时自动下载安装）"

    private const val RELATIVE_HINT = "相对路径按用户主目录解析"

    /**
     * 是否为「自动模式」配置（空 / `dsh` / `dsh.exe` / `dsh.cmd`）。
     *
     * 自动模式由 [DshServerService] 走官方客户端，不调用本对象；
     * 显式路径仍然原样解析（外部 dsh 覆盖）。
     */
    fun isAutoConfigured(configured: String): Boolean {
        val raw = configured.trim()
        return raw.isEmpty() || AUTO_NAMES.any { raw.equals(it, ignoreCase = true) }
    }

    /**
     * 解析 dsh 启动命令。
     *
     * @param configured 用户在设置里填写的命令；空白或 `dsh` / `dsh.exe` / `dsh.cmd` 进入自动模式。
     * @param pathEnv    待解析的 PATH 字符串（Windows 用 `;`，其他平台用 `:`）。
     * @param extraCandidates 自动模式在 PATH 之后的兜底候选路径。
     * @param isWindows  仅用于决定候选扩展名与可执行判断，测试显式传入。
     * @param baseDir    相对路径的解析基准，**必须与子进程 cwd 一致**：`DshServerService` 把
     *                   `ProcessBuilder.directory` 设为 `user.home`，所以这里默认也用 `user.home`。
     *                   若两边不一致，`.\bin\dsh.cmd` 会解析成功却启动失败（或反过来误报）。
     */
    fun resolve(
        configured: String,
        pathEnv: String?,
        extraCandidates: List<String> = emptyList(),
        isWindows: Boolean = SystemInfo.isWindows,
        baseDir: Path = defaultBaseDir(),
    ): DshCommandResolution {
        val raw = configured.trim()
        if (isAutoConfigured(raw)) {
            return resolveAuto(pathEnv, extraCandidates, isWindows)
        }

        val tokens = splitCommand(raw)
        if (tokens.isEmpty()) return missing(emptyList())

        val head = tokens.first()
        // 路径形态：不猜，存在即用，不存在直接报错，避免悄悄换成别的 dsh。
        if (isPathLike(head)) {
            // 绝对路径与进程 cwd 无关，保持原样判定。
            if (toPathOrNull(head)?.isAbsolute == true) {
                return if (File(head).isFile) {
                    DshCommandResolution.Resolved(tokens, SOURCE_CONFIGURED)
                } else {
                    missing(listOf(head))
                }
            }
            // 相对路径（含 Windows 的 `\bin\x` 这类盘符相对写法）必须按子进程 cwd 解析：
            // 用 IDE 进程的 cwd 判定会出现「解析成功但启动失败」或反之。
            // 路径里可能有 Windows 非法字符（`<` `>` `|` 等），resolve 会抛 InvalidPathException，
            // 此时按「找不到」处理并记录用户原始输入，绝不外抛。
            val candidate = runCatching { baseDir.resolve(head).normalize() }.getOrNull()
            return if (candidate != null && Files.isRegularFile(candidate)) {
                DshCommandResolution.Resolved(
                    listOf(candidate.toString()) + tokens.drop(1),
                    SOURCE_CONFIGURED,
                )
            } else {
                missing(listOf(candidate?.toString() ?: head))
            }
        }

        // 裸命令名：按 PATH 语义查找；与 shell 一致，命令 token 原样保留。
        val searched = LinkedHashSet<String>()
        return if (findInPath(head, pathEnv, isWindows, searched) != null) {
            DshCommandResolution.Resolved(tokens, SOURCE_PATH)
        } else {
            missing(searched)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 自动模式
    // ---------------------------------------------------------------------------------------------

    private fun resolveAuto(
        pathEnv: String?,
        extraCandidates: List<String>,
        isWindows: Boolean,
    ): DshCommandResolution {
        val searched = LinkedHashSet<String>()

        findInPath("dsh", pathEnv, isWindows, searched)?.let {
            return DshCommandResolution.Resolved(listOf(it), SOURCE_PATH)
        }

        for (candidate in extraCandidates) {
            val path = candidate.trim()
            if (path.isEmpty()) continue
            searched += path
            if (File(path).isFile) {
                return DshCommandResolution.Resolved(listOf(path), SOURCE_CANDIDATE)
            }
        }

        return missing(searched)
    }

    // ---------------------------------------------------------------------------------------------
    // PATH 查找
    // ---------------------------------------------------------------------------------------------

    /**
     * 在 PATH 中查找命令，返回命中文件的绝对路径；同时把查找过的目录记到 [searched]。
     *
     * Windows 按 `dsh.cmd` → `dsh.exe` → `dsh.bat` → `dsh` 的顺序（与 cmd 的 PATHEXT 习惯一致），
     * 非 Windows 只找 `dsh` 且要求可执行位。
     */
    private fun findInPath(
        name: String,
        pathEnv: String?,
        isWindows: Boolean,
        searched: MutableSet<String>,
    ): String? {
        for (dir in pathDirectories(pathEnv)) {
            searched += dir.toString()
            for (candidateName in pathCandidates(name, isWindows)) {
                // 命令名里可能带 `|` 等 Windows 非法字符：单个候选无效只跳过它，
                // 继续查同一目录的下一个扩展名与后续目录。
                val candidate = runCatching { dir.resolve(candidateName) }.getOrNull() ?: continue
                if (isRunnableFile(candidate, isWindows)) return candidate.toString()
            }
        }
        return null
    }

    /** 按平台给出 PATHEXT 候选；带扩展名的命令名只按原样查。 */
    private fun pathCandidates(name: String, isWindows: Boolean): List<String> {
        if (!isWindows) return listOf(name)
        if (File(name).name.contains('.')) return listOf(name)
        return listOf("$name.cmd", "$name.exe", "$name.bat", name)
    }

    /** 文件系统探测失败（权限、无效路径）一律视为「不可用」，不让异常穿出解析器。 */
    private fun isRunnableFile(path: Path, isWindows: Boolean): Boolean = runCatching {
        Files.isRegularFile(path) && (isWindows || Files.isExecutable(path))
    }.getOrDefault(false)

    /** 相对路径的默认基准；`user.home` 异常时退回当前目录，保证入口不抛异常。 */
    private fun defaultBaseDir(): Path =
        runCatching { Path.of(System.getProperty("user.home") ?: ".") }.getOrElse { Path.of(".") }

    private fun pathDirectories(pathEnv: String?): List<Path> {
        if (pathEnv.isNullOrBlank()) return emptyList()
        return pathEnv.split(File.pathSeparatorChar)
            // Windows 的 PATH 里常出现带引号的条目，去掉引号再拼路径。
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            // PATH 里可能混入含 `"` `<` `>` 的条目（安装器写坏、手改出错）。
            // 单个坏条目只跳过，不能让整条 PATH 失效。
            .mapNotNull { entry -> runCatching { File(entry).toPath() }.getOrNull() }
    }

    // ---------------------------------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------------------------------

    /** 拆分用户配置的 dsh 命令：双引号包裹、空白分隔（启动参数由 `DshServerService` 另行追加）。 */
    private fun splitCommand(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in raw) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> if (current.isNotEmpty()) {
                    out += current.toString(); current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun isPathLike(token: String): Boolean =
        token.contains('/') || token.contains('\\') || File(token).isAbsolute

    /** `Path.of` 在 Windows 上会拒绝非法字符，解析失败时按「非绝对路径」处理。 */
    private fun toPathOrNull(raw: String): Path? = runCatching { Path.of(raw) }.getOrNull()

    private fun missing(searched: Collection<String>): DshCommandResolution.Missing {
        val visited = if (searched.isEmpty()) "（PATH 为空）" else searched.joinToString(", ")
        return DshCommandResolution.Missing(
            searched = searched.toList(),
            message = "未找到 dsh 可执行文件。已查找: $visited。$RELATIVE_HINT。$HINT",
        )
    }
}
