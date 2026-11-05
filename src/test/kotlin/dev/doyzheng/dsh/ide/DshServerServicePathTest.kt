package dev.doyzheng.dsh.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/**
 * `prependToPath` 的键名大小写契约：Windows 环境变量块里 PATH 常写作 `Path`，
 * 直接按 `PATH` 写会把原值读丢（只留新目录），导致宿主里 `explorer`/`git` ENOENT。
 */
class DshServerServicePathTest {

    private val bin = Paths.get("C:", "app", "runtime", "bin")
    private val sep = File.pathSeparator

    @Test
    fun `Path 键存在时保留原值并前置新目录`() {
        val env = mutableMapOf("Path" to "C:\\Windows\\System32;C:\\Windows")
        prependToPath(env, bin)
        assertEquals(bin.toString() + sep + "C:\\Windows\\System32;C:\\Windows", env["Path"])
        assertEquals(1, env.size)
    }

    @Test
    fun `PATH 键存在时保留原值并前置新目录`() {
        val env = mutableMapOf("PATH" to "/usr/local/bin:/usr/bin")
        prependToPath(env, bin)
        assertEquals(bin.toString() + sep + "/usr/local/bin:/usr/bin", env["PATH"])
        assertEquals(1, env.size)
    }

    @Test
    fun `键缺失时新建 PATH`() {
        val env = mutableMapOf<String, String>()
        prependToPath(env, bin)
        assertEquals(bin.toString() + sep, env["PATH"])
    }

    @Test
    fun `大小写混写也能命中已有键`() {
        val env = mutableMapOf("pAtH" to "C:\\Windows")
        prependToPath(env, bin)
        assertEquals(bin.toString() + sep + "C:\\Windows", env["pAtH"])
        assertTrue(env.keys.none { it == "PATH" })
    }
}
