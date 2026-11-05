package dev.doyzheng.dsh.ide

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 确保 DSH profile 存在，且**不覆盖**用户已有内容。
 *
 * DSH 的自定义 profile 名没有内置模板（`PROFILE_TEMPLATES` 只有 web/desktop/acp/headless/sdk*），
 * `loadProfile` 遇到不存在的目录会直接报错，所以插件必须在启动前把它建好。
 *
 * 建出来的内容与 `initProfile()` 一致：
 * - `package.json`：bundles = base + web-app（等同于 `dsh web` 的组合，只是 profile 名不同）
 * - `cordis.patch.yml`：用户补丁层，初始为空数组
 * - `pnpm-workspace.yaml`：pnpm 的 hoisted 链接配置
 *
 * 这里只创建、绝不改写：用户手动改过 bundles 的 profile 必须原样保留。
 */
object DshProfileBootstrap {

    private const val BASE = "@deepseek-ai/dsh-base"
    private const val WEB_APP = "@deepseek-ai/dsh-web-app"

    private val PATCH_TEMPLATE = """
        # 本 profile 的用户补丁层（可自行编辑）
        []
    """.trimIndent() + "\n"

    private val PNPM_WORKSPACE = """
        packages:
          - .

        nodeLinker: hoisted
        autoInstallPeers: false
    """.trimIndent() + "\n"

    /** `$DSH_HOME`：`DSH_HOME` 优先，其次 `~/.dsh`。与 DSH 自身的解析保持一致。 */
    fun dshHome(): Path {
        val env = System.getenv("DSH_HOME")
        if (!env.isNullOrBlank()) return Paths.get(env.trim())
        return Paths.get(System.getProperty("user.home") ?: ".", ".dsh")
    }

    fun profileDir(profile: String): Path = dshHome().resolve("profiles").resolve(profile)

    /**
     * 幂等准备 profile。返回 profile 目录；创建失败返回 null（调用方决定是否降级）。
     */
    fun ensure(profile: String): Path? = ensureAt(profileDir(profile))

    /** 在任意目录写入默认 profile 模板（已存在的文件不改）。返回目录；失败返回 null。 */
    fun ensureAt(dir: Path): Path? = runCatching {
        Files.createDirectories(dir)
        writeIfAbsent(dir.resolve("package.json"), manifest(dir.fileName?.toString() ?: "profile"))
        writeIfAbsent(dir.resolve("cordis.patch.yml"), PATCH_TEMPLATE)
        writeIfAbsent(dir.resolve("pnpm-workspace.yaml"), PNPM_WORKSPACE)
        dir
    }.onFailure {
        thisLogger().warn("DSH profile 准备失败: $dir", it)
    }.getOrNull()

    private fun manifest(profile: String): String = """
        {
          "name": "dsh-profile-$profile",
          "private": true,
          "dependencies": {},
          "dsh": {
            "profile": {
              "bundles": ["$BASE", "$WEB_APP"]
            }
          }
        }
    """.trimIndent() + "\n"

    private fun writeIfAbsent(target: Path, content: String) {
        if (Files.exists(target)) return
        Files.write(target, content.toByteArray(Charsets.UTF_8))
    }
}
