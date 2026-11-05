package dev.doyzheng.dsh.ide.jetbrains

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 本机文件 / 进程辅助：全部是「本机纯操作」，不依赖 DSH 进程。
 *
 * 之所以集中在这里：安装插件、管理 profile 都要在 Windows 上做 junction、备份目录、
 * 关闭/启动 IDE 等动作；这些在 Kotlin 里没有跨平台一手 API，统一用 PowerShell / cmd 封装，
 * 便于集中处理引号转义与超时。
 */
internal object Fs {

    data class ShellResult(val code: Int, val output: String)

    /** 执行外部命令，合并 stderr 到 stdout，超时强杀。 */
    fun run(command: List<String>, timeoutSeconds: Long = 120): ShellResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        runCatching { process.outputStream.close() }
        val output = runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        }
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(code, output)
    }

    fun powershell(script: String, timeoutSeconds: Long = 120): ShellResult = run(
        listOf(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-Command", script,
        ),
        timeoutSeconds,
    )

    /** PowerShell 单引号字符串字面量（内部单引号翻倍）。 */
    fun ps(value: String): String = "'" + value.replace("'", "''") + "'"

    /** 时间戳，用于备份目录名：yyyyMMdd-HHmmss-SSS。 */
    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(Date())

    /** 幂等复制目录树（覆盖已存在文件）。 */
    fun copyTree(source: Path, target: Path) {
        val src = source.toAbsolutePath().normalize()
        val dst = target.toAbsolutePath().normalize()
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val relative = src.relativize(path)
                val destination = dst.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let { Files.createDirectories(it) }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    fun exists(path: Path): Boolean = runCatching { Files.exists(path) }.getOrDefault(false)

    fun readText(path: Path): String? = runCatching {
        if (Files.isRegularFile(path)) Files.readString(path, Charsets.UTF_8) else null
    }.getOrNull()

    fun writeText(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, content, Charsets.UTF_8)
    }

    fun deleteRecursively(path: Path) {
        if (!exists(path)) return
        // 优先用 cmd rmdir（对 junction 只删链接不删目标）；普通目录再走 Files.walk 兜底。
        if (SystemInfo.isWindows && isReparsePoint(path)) {
            runCatching { run(listOf("cmd.exe", "/c", "rmdir", path.toString()), 30) }
            if (!exists(path)) return
        }
        runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /** 是否重解析点（junction / 符号链接）。仅 Windows 有效；其他平台用 NIO 判断。 */
    fun isReparsePoint(path: Path): Boolean {
        if (!SystemInfo.isWindows) return runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)
        return runCatching {
            val attrs = Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            attrs.isOther || attrs.isSymbolicLink
        }.getOrDefault(runCatching { Files.isSymbolicLink(path) }.getOrDefault(false))
    }
}

/** junction / 符号链接的探测与创建（Windows 用 junction，免管理员权限）。 */
internal object Links {

    data class Info(val kind: String, val target: String?) // kind: missing|link|dir|file

    fun inspect(path: Path): Info {
        if (!SystemInfo.isWindows) return inspectNio(path)
        val script = """
            ${'$'}p = Get-Item -LiteralPath ${Fs.ps(path.toString())} -Force -ErrorAction SilentlyContinue
            if (${'$'}p -eq ${'$'}null) { 'MISSING' }
            elseif (${'$'}p.Attributes -band [IO.FileAttributes]::ReparsePoint) {
              ${'$'}t = ${'$'}p.Target
              if (${'$'}t -is [array]) { ${'$'}t = ${'$'}t[0] }
              'LINK|' + [string]${'$'}t
            }
            elseif (${'$'}p.PSIsContainer) { 'DIR' } else { 'FILE' }
        """.trimIndent()
        val line = Fs.powershell(script, 30).output.trim()
            .lineSequence().lastOrNull { it.isNotBlank() } ?: "MISSING"
        return when {
            line == "MISSING" -> Info("missing", null)
            line.startsWith("LINK|") -> Info("link", line.removePrefix("LINK|").trim().ifBlank { null })
            line == "DIR" -> Info("dir", null)
            line == "FILE" -> Info("file", null)
            else -> Info("missing", null)
        }
    }

    private fun inspectNio(path: Path): Info = runCatching {
        if (Files.isSymbolicLink(path)) return Info("link", runCatching { Files.readSymbolicLink(path).toString() }.getOrNull())
        when {
            !Files.exists(path) -> Info("missing", null)
            Files.isDirectory(path) -> Info("dir", null)
            else -> Info("file", null)
        }
    }.getOrDefault(Info("missing", null))

    /** 创建指向 target 的目录链接（Windows junction），要求 target 已是真实目录的绝对路径。 */
    fun createJunction(link: Path, target: Path) {
        link.parent?.let { Files.createDirectories(it) }
        if (SystemInfo.isWindows) {
            val result = Fs.powershell(
                "New-Item -ItemType Junction -Path ${Fs.ps(link.toString())} -Target ${Fs.ps(target.toString())} | Out-Null",
                30,
            )
            if (result.code != 0) error("创建 junction 失败: ${result.output.trim()}")
        } else {
            Files.createSymbolicLink(link, target)
        }
    }

    /** 删除链接本身（不删除目标内容）。 */
    fun deleteLink(link: Path) {
        if (SystemInfo.isWindows) {
            Fs.run(listOf("cmd.exe", "/c", "rmdir", link.toString()), 30)
        } else {
            Files.deleteIfExists(link)
        }
    }
}
