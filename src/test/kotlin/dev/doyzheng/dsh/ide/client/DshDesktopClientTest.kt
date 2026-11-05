package dev.doyzheng.dsh.ide.client

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DshDesktopClient` 的定位契约：注册表解析、产品名匹配、两种安装布局、runtime.json 读取。
 * 全部用临时目录与合成文本，不依赖本机是否真的装了客户端。
 */
class DshDesktopClientTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 造一个 Windows 布局的安装目录。 */
    private fun windowsInstall(root: Path, desktopVersion: String? = "0.2.2-rc.1", node: String? = "24.21.0"): Path {
        Files.createFile(root.resolve("DeepSeek Harness.exe"))
        val resources = Files.createDirectories(root.resolve("resources"))
        Files.createFile(resources.resolve("app.asar"))
        if (desktopVersion != null || node != null) {
            val runtime = Files.createDirectories(resources.resolve("runtime").resolve("primary-runtime"))
            val json = buildString {
                append("{\n")
                append("  \"desktopVersion\": \"${desktopVersion ?: ""}\",\n")
                append("  \"node\": \"${node ?: ""}\"\n")
                append("}\n")
            }
            Files.writeString(runtime.resolve("runtime.json"), json)
        }
        return root
    }

    @Test
    fun `注册表输出按块解析出名称与安装目录`() {
        val output = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{aaa}
                DisplayName    REG_SZ    DeepSeek Harness
                DisplayVersion    REG_SZ    0.2.2-rc.1
                InstallLocation    REG_SZ    D:\apps\dsh

            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\{bbb}
                DisplayName    REG_SZ    Some Other App
                InstallLocation    REG_SZ    C:\Other
        """.trimIndent()

        val apps = DshDesktopClient.parseRegQuery(output)

        assertEquals(2, apps.size)
        assertEquals("DeepSeek Harness", apps[0].displayName)
        assertEquals("D:\\apps\\dsh", apps[0].installLocation)
        assertEquals("0.2.2-rc.1", apps[0].displayVersion)
        assertEquals("Some Other App", apps[1].displayName)
    }

    @Test
    fun `产品名只匹配 DeepSeek Harness`() {
        assertTrue(DshDesktopClient.isProduct("DeepSeek Harness"))
        assertTrue(DshDesktopClient.isProduct("deepseek-harness"))
        assertFalse(DshDesktopClient.isProduct("Some Other App"))
        assertFalse(DshDesktopClient.isProduct(null))
    }

    @Test
    fun `识别 Windows 布局并读取版本与 Node`() {
        val dir = windowsInstall(temporaryFolder.newFolder("win").toPath())

        val layout = DshDesktopClient.layoutOf(dir)
        assertNotNull("应识别 Windows 布局", layout)
        assertEquals("DeepSeek Harness.exe", layout!!.first.fileName.toString())

        val install = DshDesktopClient.fromInstallDir(dir)
        assertNotNull(install)
        assertEquals("0.2.2-rc.1", install!!.version)
        assertEquals("24.21.0", install.nodeVersion)
        assertTrue(install.cliEntry.toString().replace('\\', '/').endsWith("dsh-desktop-host/lib/cli.js"))
    }

    @Test
    fun `识别 macOS app 布局`() {
        val dir = temporaryFolder.newFolder("mac").toPath()
        Files.createDirectories(dir.resolve("Contents").resolve("MacOS"))
        Files.createFile(dir.resolve("Contents").resolve("MacOS").resolve("DeepSeek Harness"))
        val resources = Files.createDirectories(dir.resolve("Contents").resolve("Resources"))
        Files.createFile(resources.resolve("app.asar"))

        val layout = DshDesktopClient.layoutOf(dir)

        assertNotNull("应识别 macOS 布局", layout)
        assertEquals("DeepSeek Harness", layout!!.first.fileName.toString())
    }

    @Test
    fun `目录缺少 exe 或 app_asar 时不算安装`() {
        val empty = temporaryFolder.newFolder("empty").toPath()
        assertNull(DshDesktopClient.layoutOf(empty))
        assertNull(DshDesktopClient.fromInstallDir(empty))

        val onlyAsar = temporaryFolder.newFolder("only-asar").toPath()
        Files.createDirectories(onlyAsar.resolve("resources"))
        Files.createFile(onlyAsar.resolve("resources").resolve("app.asar"))
        assertNull(DshDesktopClient.layoutOf(onlyAsar))
    }

    @Test
    fun `detect 优先使用注册表指向的自定义安装目录`() {
        val dir = windowsInstall(temporaryFolder.newFolder("custom").toPath(), "0.2.2-rc.1", "24.21.0")
        val registry = listOf(RegistryApp("DeepSeek Harness", dir.toString(), "0.2.2-rc.1"))
        val otherEmpty = temporaryFolder.newFolder("not-installed").toPath()

        val found = DshDesktopClient.detect(listOf(otherEmpty), registry)

        assertNotNull(found)
        assertEquals(dir.toAbsolutePath().normalize(), found!!.installDir)
        assertEquals("registry", found.source)
    }

    @Test
    fun `detect 在没有注册表时回落到候选目录`() {
        val dir = windowsInstall(temporaryFolder.newFolder("by-path").toPath())

        val found = DshDesktopClient.detect(listOf(dir), emptyList())

        assertNotNull(found)
        assertEquals(dir.toAbsolutePath().normalize(), found!!.installDir)
        assertEquals("path", found.source)
    }

    @Test
    fun `未安装任何客户端时 detect 返回 null`() {
        val empty = temporaryFolder.newFolder("none").toPath()
        assertNull(DshDesktopClient.detect(listOf(empty), emptyList()))
    }
}
