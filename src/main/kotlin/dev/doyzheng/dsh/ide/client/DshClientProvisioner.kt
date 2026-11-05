package dev.doyzheng.dsh.ide.client

import com.intellij.openapi.util.SystemInfo
import dev.doyzheng.dsh.ide.jetbrains.Fs
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * 确保官方 DeepSeek Harness 客户端可用：已安装直接复用；未安装则按系统下载安装包并安装。
 *
 * 下载地址（官方稳定入口）：
 *  - Windows x64：https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe
 *  - macOS arm64：https://download.deepseek.com/desktop/dsh-latest-macos-arm64.dmg
 *
 * **必须在后台线程调用**：首次安装要下载上百 MB 的安装包。
 */
object DshClientProvisioner {

    const val WINDOWS_X64_URL = "https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe"
    const val MACOS_ARM64_URL = "https://download.deepseek.com/desktop/dsh-latest-macos-arm64.dmg"

    /** 返回可用的客户端；未安装则下载并安装后返回。失败抛异常（消息面向用户）。 */
    fun ensure(status: (String) -> Unit = {}): DshClientInstall {
        DshDesktopClient.locate()?.let {
            status("已检测到 DeepSeek Harness 客户端 ${it.version ?: ""}".trim())
            return it
        }
        val url = downloadUrl()
            ?: throw IllegalStateException(
                "当前系统（${System.getProperty("os.name")}）暂不支持自动安装 DeepSeek Harness，请手动安装客户端后重试",
            )
        status("未检测到 DeepSeek Harness 客户端，开始下载…")
        val installer = downloadInstaller(url, status)
        status("正在安装 DeepSeek Harness 客户端…")
        install(installer, status)
        status("等待客户端安装完成…")
        val installed = waitForInstall(status)
            ?: throw IllegalStateException(
                "安装已执行但未检测到 DeepSeek Harness。请手动运行安装包完成安装后重启 DSH：$installer",
            )
        // 安装成功：删掉临时安装包（单个约 276 MB，重复自动安装会在 TEMP 里线性累积）。
        // 失败路径不删：异常信息要求用户手动运行该安装包。
        runCatching { Files.deleteIfExists(installer) }
        return installed
    }

    /** 按平台选择下载地址；显式传参便于单测。 */
    internal fun downloadUrl(
        isWindows: Boolean = SystemInfo.isWindows,
        isMac: Boolean = SystemInfo.isMac,
        isArm64: Boolean = isArm64(),
    ): String? = when {
        isWindows -> WINDOWS_X64_URL
        isMac && isArm64 -> MACOS_ARM64_URL
        else -> null
    }

    internal fun isArm64(): Boolean {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return arch == "aarch64" || arch == "arm64"
    }

    // ---------------------------------------------------------------------------------------------
    // 下载
    // ---------------------------------------------------------------------------------------------

    private fun downloadInstaller(url: String, status: (String) -> Unit): Path {
        val suffix = when {
            url.endsWith(".dmg", ignoreCase = true) -> ".dmg"
            url.endsWith(".exe", ignoreCase = true) -> ".exe"
            else -> ".bin"
        }
        val target = Files.createTempFile("dsh-ide-client-", suffix)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "dsh-idea-plugin")
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载客户端失败：HTTP $code（$url）")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { buffered ->
                    Files.newOutputStream(target).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = buffered.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent && percent % 5 == 0) {
                                    lastPercent = percent
                                    status("正在下载客户端… $percent%")
                                }
                            } else {
                                status("正在下载客户端… ${copied / (1024 * 1024)} MB")
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { connection.disconnect() }
        }
        return target
    }

    // ---------------------------------------------------------------------------------------------
    // 安装
    // ---------------------------------------------------------------------------------------------

    private fun install(installer: Path, status: (String) -> Unit) {
        if (SystemInfo.isWindows) installWindows(installer) else installMac(installer, status)
    }

    /** Windows：NSIS 安装包，`/S` 静默安装。静默失败时打开安装向导并提示用户完成。 */
    private fun installWindows(installer: Path) {
        val process = ProcessBuilder(installer.toString(), "/S")
            .redirectErrorStream(true)
            .start()
        // 读空输出，避免管道缓冲区写满阻塞安装进程。
        runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val finished = runCatching { process.waitFor(5, TimeUnit.MINUTES) }.getOrDefault(false)
        if (!finished) {
            // 可能在等待用户交互，保留安装进程不杀，交给用户完成。
            runCatching { java.awt.Desktop.getDesktop().open(installer.toFile()) }
            throw IllegalStateException("客户端安装未在预期时间内完成，已打开安装向导，请完成后重启 DSH")
        }
        if (process.exitValue() != 0) {
            runCatching { java.awt.Desktop.getDesktop().open(installer.toFile()) }
            throw IllegalStateException(
                "客户端静默安装失败（code=${process.exitValue()}），已打开安装向导，请完成安装后重启 DSH",
            )
        }
    }

    /** macOS：挂载 dmg，把 .app 复制到 /Applications（无权限时退到 ~/Applications），再卸载。 */
    private fun installMac(dmg: Path, status: (String) -> Unit) {
        val mountPoint = Files.createTempDirectory("dsh-ide-dmg-")
        try {
            val attach = Fs.run(
                listOf("hdiutil", "attach", "-nobrowse", "-quiet", "-mountpoint", mountPoint.toString(), dmg.toString()),
                timeoutSeconds = 120,
            )
            if (attach.code != 0) {
                throw IllegalStateException("挂载 dmg 失败：${attach.output.trim()}")
            }
            val app = runCatching {
                Files.newDirectoryStream(mountPoint).use { stream ->
                    stream.firstOrNull { it.fileName.toString().endsWith(".app") }
                }
            }.getOrNull() ?: throw IllegalStateException("dmg 内未找到 .app")
            val appName = app.fileName.toString()
            val applications = Paths.get("/Applications")
            val targetRoot = if (Files.isWritable(applications)) applications else Paths.get(
                System.getProperty("user.home") ?: ".",
                "Applications",
            )
            Files.createDirectories(targetRoot)
            val destination = targetRoot.resolve(appName)
            status("正在复制 $appName 到 $targetRoot …")
            val copy = Fs.run(listOf("ditto", app.toString(), destination.toString()), timeoutSeconds = 600)
            if (copy.code != 0) {
                throw IllegalStateException("复制应用失败：${copy.output.trim()}")
            }
        } finally {
            runCatching { Fs.run(listOf("hdiutil", "detach", mountPoint.toString(), "-quiet"), timeoutSeconds = 60) }
            runCatching { Fs.deleteRecursively(mountPoint) }
        }
    }

    /** 安装结束后轮询定位，最多约 2 分钟。 */
    private fun waitForInstall(status: (String) -> Unit): DshClientInstall? {
        repeat(60) { index ->
            DshDesktopClient.locate()?.let { return it }
            if (index % 5 == 0) status("等待客户端安装完成… ${index * 2}s")
            Thread.sleep(2_000)
        }
        return DshDesktopClient.locate()
    }
}
