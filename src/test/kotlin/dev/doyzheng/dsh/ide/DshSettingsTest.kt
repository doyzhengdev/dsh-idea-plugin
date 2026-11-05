package dev.doyzheng.dsh.ide

import org.junit.Assert.assertEquals
import org.junit.Test

/** `DshSettings` 的默认值与加载契约。 */
class DshSettingsTest {

    @Test
    fun `默认 profile 为 idea`() {
        assertEquals("idea", DshSettings().current.profile)
        assertEquals("idea", DshSettings.DEFAULT_PROFILE)
    }

    @Test
    fun `默认走官方客户端自动模式`() {
        assertEquals("dsh", DshSettings().current.dshCommand)
    }

    @Test
    fun `默认端口为 46231`() {
        assertEquals(46231, DshSettings().current.port)
        assertEquals(46231, DshSettings.DEFAULT_PORT)
    }

    @Test
    fun `加载时原样保留持久化值`() {
        val settings = DshSettings()
        settings.loadState(DshSettings.Settings(profile = "custom", port = 50000, dshCommand = "C:\\dsh\\dsh.cmd"))
        assertEquals("custom", settings.current.profile)
        assertEquals(50000, settings.current.port)
        assertEquals("C:\\dsh\\dsh.cmd", settings.current.dshCommand)
    }

    @Test
    fun `空 profile 原样保留由服务层兜底`() {
        val settings = DshSettings()
        settings.loadState(DshSettings.Settings(profile = ""))
        assertEquals("", settings.current.profile)
    }
}
