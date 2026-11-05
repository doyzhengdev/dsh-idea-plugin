package dev.doyzheng.dsh.ide

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `DshCommandResolver` 的解析契约：显式配置优先、PATH 次之、兜底候选最后，
 * 失败时给出可操作的信息。
 *
 * 所有用例都显式传 `isWindows` 与 `extraCandidates`，不依赖本机环境。
 */
class DshCommandResolverTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDir(name: String): Path = temporaryFolder.newFolder(name).toPath()

    private fun touch(dir: Path, name: String): Path =
        Files.createFile(dir.resolve(name))

    /** 组装一个平台无关的 PATH 字符串。 */
    private fun pathOf(vararg dirs: Path): String =
        dirs.joinToString(File.pathSeparator) { it.toString() }

    private fun resolved(result: DshCommandResolution): DshCommandResolution.Resolved {
        assertTrue("期望 Resolved，实际是 $result", result is DshCommandResolution.Resolved)
        return result as DshCommandResolution.Resolved
    }

    private fun missing(result: DshCommandResolution): DshCommandResolution.Missing {
        assertTrue("期望 Missing，实际是 $result", result is DshCommandResolution.Missing)
        return result as DshCommandResolution.Missing
    }

    @Test
    fun `显式绝对路径存在时按配置解析`() {
        val dir = newDir("explicit")
        val dsh = touch(dir, "dsh.cmd")

        val result = DshCommandResolver.resolve(
            configured = dsh.toString(),
            pathEnv = null,
            extraCandidates = emptyList(),
            isWindows = true,
        )

        assertEquals(listOf(dsh.toString()), resolved(result).command)
        assertEquals("配置", resolved(result).source)
    }

    @Test
    fun `带引号且含空格的路径被切成单个 token`() {
        val dir = newDir("with space")
        val dsh = touch(dir, "dsh.cmd")

        val result = DshCommandResolver.resolve(
            configured = "\"$dsh\" --trace",
            pathEnv = null,
            extraCandidates = emptyList(),
            isWindows = true,
        )

        assertEquals(listOf(dsh.toString(), "--trace"), resolved(result).command)
        assertEquals("配置", resolved(result).source)
    }

    @Test
    fun `显式路径不存在时返回 Missing 并带上该路径`() {
        val missingPath = newDir("nowhere").resolve("dsh.cmd").toString()

        val result = DshCommandResolver.resolve(
            configured = missingPath,
            pathEnv = null,
            extraCandidates = emptyList(),
            isWindows = true,
        )

        val problem = missing(result)
        assertTrue("searched 应包含配置的路径", problem.searched.contains(missingPath))
        assertTrue("message 不应为空", problem.message.isNotBlank())
        assertTrue("message 应包含该路径", problem.message.contains(missingPath))
        assertTrue("message 应说明相对路径的基准", problem.message.contains("相对路径按用户主目录解析"))
    }

    @Test
    fun `自动模式下 PATH 中的 dsh_cmd 命中`() {
        val bin = newDir("path-bin")
        val dsh = touch(bin, "dsh.cmd")

        val result = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = pathOf(bin),
            extraCandidates = emptyList(),
            isWindows = true,
        )

        assertEquals(listOf(dsh.toString()), resolved(result).command)
        assertEquals("PATH", resolved(result).source)
    }

    @Test
    fun `自动模式下 PATH 为空时回落到兜底候选`() {
        val runtime = newDir("runtime").resolve("dsh.cmd")
        Files.createFile(runtime)

        val result = DshCommandResolver.resolve(
            configured = "dsh.cmd",
            pathEnv = "",
            extraCandidates = listOf(runtime.toString()),
            isWindows = true,
        )

        assertEquals(listOf(runtime.toString()), resolved(result).command)
        assertEquals("候选路径", resolved(result).source)
    }

    @Test
    fun `解析优先级为配置高于 PATH 高于 extra`() {
        val bin = newDir("bin")
        val onPath = touch(bin, "dsh.cmd")
        val configuredDir = newDir("configured")
        val configured = touch(configuredDir, "dsh.cmd")
        val extra = newDir("extra").resolve("dsh.cmd")
        Files.createFile(extra)

        val byConfig = DshCommandResolver.resolve(
            configured = configured.toString(),
            pathEnv = pathOf(bin),
            extraCandidates = listOf(extra.toString()),
            isWindows = true,
        )
        assertEquals(listOf(configured.toString()), resolved(byConfig).command)
        assertEquals("配置", resolved(byConfig).source)

        val byPath = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = pathOf(bin),
            extraCandidates = listOf(extra.toString()),
            isWindows = true,
        )
        assertEquals(listOf(onPath.toString()), resolved(byPath).command)
        assertEquals("PATH", resolved(byPath).source)
    }

    @Test
    fun `全部找不到时 searched 含 PATH 目录与 extra 且给出设置提示`() {
        val first = newDir("empty-1")
        val second = newDir("empty-2")
        val extra = newDir("empty-runtime").resolve("dsh.cmd").toString()

        val result = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = pathOf(first, second),
            extraCandidates = listOf(extra),
            isWindows = true,
        )

        val problem = missing(result)
        assertTrue("searched 应含第一个 PATH 目录", problem.searched.contains(first.toString()))
        assertTrue("searched 应含第二个 PATH 目录", problem.searched.contains(second.toString()))
        assertTrue("searched 应含 extra", problem.searched.contains(extra))
        assertTrue(
            "message 应提示去设置里配置: ${problem.message}",
            problem.message.contains("Settings > Tools > DSH"),
        )
    }

    @Test
    fun `显式裸命令名命中 PATH 时保留用户写的 token`() {
        val bin = newDir("bare-bin")
        touch(bin, "dsh-dev.cmd")

        val result = DshCommandResolver.resolve(
            configured = "dsh-dev --verbose",
            pathEnv = pathOf(bin),
            extraCandidates = emptyList(),
            isWindows = true,
        )

        assertEquals(listOf("dsh-dev", "--verbose"), resolved(result).command)
        assertEquals("PATH", resolved(result).source)
    }

    // ---------------------------------------------------------------------------------------------
    // 相对路径按 baseDir（= 子进程 cwd）解析
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `相对路径按 baseDir 解析并返回绝对路径`() {
        val base = newDir("home")
        val bin = Files.createDirectories(base.resolve("bin"))
        val dsh = Files.createFile(bin.resolve("dsh.cmd"))

        val result = DshCommandResolver.resolve(
            configured = "bin/dsh.cmd --trace",
            pathEnv = null,
            extraCandidates = emptyList(),
            isWindows = true,
            baseDir = base,
        )

        val command = resolved(result).command
        assertEquals("应解析成 baseDir 下的绝对路径", dsh.toString(), command.first())
        assertTrue("首个 token 必须是绝对路径", File(command.first()).isAbsolute)
        assertEquals("配置", resolved(result).source)
        assertEquals("剩余参数原样保留", listOf("--trace"), command.drop(1))
    }

    @Test
    fun `相对路径在 baseDir 下不存在时返回 Missing`() {
        val base = newDir("home-missing")

        val result = DshCommandResolver.resolve(
            configured = ".\\bin\\dsh.cmd",
            pathEnv = null,
            extraCandidates = emptyList(),
            isWindows = true,
            baseDir = base,
        )

        val problem = missing(result)
        val expected = base.resolve(".\\bin\\dsh.cmd").normalize().toString()
        assertTrue("searched 应含归一化后的绝对路径: ${problem.searched}", problem.searched.contains(expected))
        assertTrue("message 应说明相对路径的基准", problem.message.contains("相对路径按用户主目录解析"))
    }

    // ---------------------------------------------------------------------------------------------
    // 非 Windows 的可执行位
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `非 Windows 下只认带执行位的 dsh`() {
        val bin = newDir("posix-bin")
        val plain = touch(bin, "dsh")

        // 先造一个不可执行的文件：POSIX 用权限位，Windows 用 ACL 拒绝 EXECUTE
        // （Windows 没有 POSIX 权限模型，但仍要让本机跑出同样的否定分支）。
        assertTrue("无法移除执行权限，用例失去意义", makeNonExecutable(plain))
        assertFalse("前置条件：文件应不可执行", Files.isExecutable(plain))

        val skipped = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = pathOf(bin),
            extraCandidates = emptyList(),
            isWindows = false,
        )
        val problem = missing(skipped)
        assertTrue("不可执行的文件不得命中", problem.searched.contains(bin.toString()))

        // 补上执行位后应命中，且返回绝对路径。
        makeExecutable(plain)
        assertTrue("前置条件：文件应可执行", Files.isExecutable(plain))

        val hit = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = pathOf(bin),
            extraCandidates = emptyList(),
            isWindows = false,
        )
        assertEquals(plain.toString(), resolved(hit).command.single())
        assertEquals("PATH", resolved(hit).source)
    }

    @Test
    fun `PATH 条目带双引号时去掉引号仍能命中`() {
        val bin = newDir("quoted bin")
        val dsh = touch(bin, "dsh.cmd")

        val result = DshCommandResolver.resolve(
            configured = "dsh",
            pathEnv = "\"$bin\"",
            extraCandidates = emptyList(),
            isWindows = true,
        )

        assertEquals(listOf(dsh.toString()), resolved(result).command)
        assertEquals("PATH", resolved(result).source)
    }

    // ---------------------------------------------------------------------------------------------
    // 非法路径字符：解析器必须只返回 Missing，绝不外抛 InvalidPathException
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `裸命令名含竖线时返回 Missing 而不抛异常`() {
        val bin = newDir("invalid-bare")
        val result = runCatching {
            DshCommandResolver.resolve(
                configured = "dsh|x",
                pathEnv = pathOf(bin),
                extraCandidates = emptyList(),
                isWindows = true,
            )
        }

        assertTrue("解析器不得抛出异常: ${result.exceptionOrNull()}", result.isSuccess)
        val problem = missing(result.getOrThrow())
        assertTrue("searched 应记录查过的目录", problem.searched.contains(bin.toString()))
        assertTrue("message 不应为空", problem.message.isNotBlank())
    }

    @Test
    fun `相对路径含尖括号时返回 Missing 而不抛异常`() {
        val base = newDir("invalid-relative")
        val result = runCatching {
            DshCommandResolver.resolve(
                configured = ".\\bin<dsh>\\dsh.cmd --trace",
                pathEnv = null,
                extraCandidates = emptyList(),
                isWindows = true,
                baseDir = base,
            )
        }

        assertTrue("解析器不得抛出异常: ${result.exceptionOrNull()}", result.isSuccess)
        val problem = missing(result.getOrThrow())
        assertTrue("searched 应保留出错的输入: ${problem.searched}", problem.searched.any { it.contains("bin<dsh>") })
        assertTrue("message 不应为空", problem.message.isNotBlank())
    }

    @Test
    fun `PATH 中的非法条目被跳过且后续合法目录仍能命中`() {
        val bin = newDir("after-invalid-path")
        val dsh = touch(bin, "dsh.cmd")
        val badEntry = "C:\\bad|entry"
        val pathEnv = listOf(badEntry, bin.toString()).joinToString(File.pathSeparator) { "\"$it\"" }

        val result = runCatching {
            DshCommandResolver.resolve(
                configured = "dsh",
                pathEnv = pathEnv,
                extraCandidates = emptyList(),
                isWindows = true,
            )
        }

        assertTrue("解析器不得抛出异常: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf(dsh.toString()), resolved(result.getOrThrow()).command)
        assertEquals("PATH", resolved(result.getOrThrow()).source)
    }

    @Test
    fun `Path_of 拒绝 NUL 字符且解析器将其转成 Missing`() {
        // NUL 是唯一在所有平台都被 Path.of 拒绝的字符，用它复现 InvalidPathException 不依赖本机环境。
        val illegal = "dsh\u0000x"
        val rejection = runCatching { Path.of(illegal) }
        assertTrue(
            "前置条件：NUL 应被 Path.of 拒绝，实际 ${rejection.exceptionOrNull()}",
            rejection.exceptionOrNull() is InvalidPathException,
        )

        val bin = newDir("nul-bin")
        val asBare = runCatching {
            DshCommandResolver.resolve(illegal, pathOf(bin), emptyList(), true)
        }
        assertTrue("裸命令名含 NUL 时不得抛异常: ${asBare.exceptionOrNull()}", asBare.isSuccess)
        missing(asBare.getOrThrow())

        val base = newDir("nul-relative")
        val asPath = runCatching {
            DshCommandResolver.resolve(
                configured = ".\\bin\\dsh\u0000.cmd",
                pathEnv = null,
                extraCandidates = emptyList(),
                isWindows = true,
                baseDir = base,
            )
        }
        assertTrue("相对路径含 NUL 时不得抛异常: ${asPath.exceptionOrNull()}", asPath.isSuccess)
        missing(asPath.getOrThrow())
    }

    // ---------------------------------------------------------------------------------------------
    // 可执行位工具
    // ---------------------------------------------------------------------------------------------

    /** Windows 没有 POSIX 权限模型，用 ACL 拒绝当前用户的 EXECUTE 达到同样效果。 */
    private fun makeNonExecutable(file: Path): Boolean {
        val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (posix != null) {
            Files.setPosixFilePermissions(
                file,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            return true
        }
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        assumeNotNull("当前文件系统既无 POSIX 也无 ACL 视图，跳过", acl)
        val deny = AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(acl!!.owner)
            .setPermissions(EnumSet.of(AclEntryPermission.EXECUTE))
            .build()
        val entries = ArrayList<AclEntry>(acl.acl)
        entries.add(0, deny)
        acl.acl = entries
        return true
    }

    private fun makeExecutable(file: Path) {
        val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (posix != null) {
            Files.setPosixFilePermissions(
                file,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            return
        }
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java) ?: return
        acl.acl = acl.acl.filterNot {
            it.type() == AclEntryType.DENY && it.permissions().contains(AclEntryPermission.EXECUTE)
        }
    }
}
