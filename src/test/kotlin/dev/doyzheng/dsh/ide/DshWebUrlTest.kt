package dev.doyzheng.dsh.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DshWebUrl` 的编码契约：参数进 fragment、Windows 路径与空格/中文可正确还原。
 */
class DshWebUrlTest {

    @Test
    fun `带 token 的 base 与 Windows 路径`() {
        val base = "http://127.0.0.1:45231/?token=wc_abc123"
        val url = DshWebUrl.workspaceUrl(base, "E:\\work\\dsh-ide")

        assertEquals(
            "http://127.0.0.1:45231/?token=wc_abc123#ideWorkspace=E%3A%5Cwork%5Cdsh-ide",
            url,
        )
        assertTrue(url.contains("#" + DshWebUrl.WORKSPACE_PARAM + "="))
    }

    @Test
    fun `空格编码为 %20 而不是加号且中文按 UTF-8`() {
        val url = DshWebUrl.workspaceUrl("http://127.0.0.1:45231/", "E:\\我的 项目")

        assertEquals(
            "http://127.0.0.1:45231/#ideWorkspace=E%3A%5C%E6%88%91%E7%9A%84%20%E9%A1%B9%E7%9B%AE",
            url,
        )
        assertTrue("空格必须编码成 %20", url.contains("%20"))
        assertTrue("不得出现表单式加号", !url.contains("+"))
    }

    @Test
    fun `路径为空或空白时原样返回 base`() {
        val base = "http://127.0.0.1:45231/?token=wc_abc123"
        assertEquals(base, DshWebUrl.workspaceUrl(base, null))
        assertEquals(base, DshWebUrl.workspaceUrl(base, ""))
        assertEquals(base, DshWebUrl.workspaceUrl(base, "   "))
    }

    @Test
    fun `base 已有 fragment 时先去掉再追加`() {
        val url = DshWebUrl.workspaceUrl("http://127.0.0.1:45231/?token=x#old-route", "E:\\p")

        assertEquals(
            "http://127.0.0.1:45231/?token=x#ideWorkspace=E%3A%5Cp",
            url,
        )
    }

    @Test
    fun `没有 query 的 base 也能追加`() {
        assertEquals(
            "http://127.0.0.1:45231#ideWorkspace=E%3A%5Cp",
            DshWebUrl.workspaceUrl("http://127.0.0.1:45231", "E:\\p"),
        )
    }
}
