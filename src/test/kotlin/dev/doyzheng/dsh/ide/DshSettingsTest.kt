package dev.doyzheng.dsh.ide

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DshSettings` 的 profile 契约：默认值走 `idea`，历史值 `dsh-ide` 在加载时迁移，
 * 其余取值（含空串）原样保留，由服务层兜底。
 */
class DshSettingsTest {

    @Test
    fun `新实例默认 profile 为 idea`() {
        assertEquals("idea", DshSettings().current.profile)
        assertEquals("idea", DshSettings.DEFAULT_PROFILE)
    }

    @Test
    fun `加载历史值 dsh-ide 迁移为 idea`() {
        val settings = DshSettings()

        settings.loadState(DshSettings.Settings(profile = "dsh-ide"))

        assertEquals("idea", settings.current.profile)
    }

    @Test
    fun `加载自定义 profile 不做改动`() {
        val settings = DshSettings()

        settings.loadState(DshSettings.Settings(profile = "custom"))

        assertEquals("custom", settings.current.profile)
    }

    @Test
    fun `加载空 profile 保持为空由服务层兜底`() {
        val settings = DshSettings()

        settings.loadState(DshSettings.Settings(profile = ""))

        assertEquals("", settings.current.profile)
    }
}
