package dev.doyzheng.dsh.ide.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `DshClientProvisioner` 的平台下载地址选择契约（不触发网络）。 */
class DshClientProvisionerTest {

    @Test
    fun `Windows 使用 exe 安装包地址`() {
        assertEquals(
            DshClientProvisioner.WINDOWS_X64_URL,
            DshClientProvisioner.downloadUrl(isWindows = true, isMac = false, isArm64 = false),
        )
    }

    @Test
    fun `macOS arm64 使用 dmg 地址`() {
        assertEquals(
            DshClientProvisioner.MACOS_ARM64_URL,
            DshClientProvisioner.downloadUrl(isWindows = false, isMac = true, isArm64 = true),
        )
    }

    @Test
    fun `macOS intel 没有可用安装包`() {
        assertNull(DshClientProvisioner.downloadUrl(isWindows = false, isMac = true, isArm64 = false))
    }

    @Test
    fun `其它系统没有自动安装地址`() {
        assertNull(DshClientProvisioner.downloadUrl(isWindows = false, isMac = false, isArm64 = true))
    }

    @Test
    fun `地址常量与官方下载入口一致`() {
        assertEquals(
            "https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe",
            DshClientProvisioner.WINDOWS_X64_URL,
        )
        assertEquals(
            "https://download.deepseek.com/desktop/dsh-latest-macos-arm64.dmg",
            DshClientProvisioner.MACOS_ARM64_URL,
        )
    }
}
