package dev.doyzheng.dsh.ide.jetbrains

import dev.doyzheng.dsh.ide.DshProfileBootstrap
import java.nio.file.Files
import java.nio.file.Path

data class ProfileResult(
    val ok: Boolean,
    val steps: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * DSH profile 管理（本机文件操作，不需要 DSH 运行）。
 *
 * 目标 profile（默认 `idea`）是指向桌面端 `desktop` profile 的目录链接（Windows junction），
 * 从而与桌面端共用凭据、插件与会话配置。
 */
object ProfileManager {

    fun profilesRoot(): Path = DshProfileBootstrap.dshHome().resolve("profiles")

    /**
     * 确保目标 profile 是指向来源（默认 `desktop`）的目录链接。
     *
     *  1. 来源不存在 → 按官方桌面端模板创建；
     *  2. 目标缺失 → 建 junction；
     *  3. 目标已是链接 → 有效则保持，断链则重建；
     *  4. 目标是普通目录 → 报错（不删除用户数据）；
     *  5. 目标是文件 → 报错。
     *
     * 失败由调用方记日志，不阻塞启动。
     */
    fun ensureLinked(target: String, source: String = "desktop"): ProfileResult {
        if (target == source) {
            return ProfileResult(false, error = "目标 profile 与来源相同：$target")
        }
        val root = profilesRoot()
        Files.createDirectories(root)
        val targetDir = root.resolve(target)
        val sourceDir = root.resolve(source)
        val steps = mutableListOf<String>()
        return try {
            ensureSource(sourceDir, steps)?.let { return it }

            val real = sourceDir.toRealPath()
            when (Links.inspect(targetDir).kind) {
                "missing" -> {
                    Links.createJunction(targetDir, real)
                    steps += "已创建目录链接 $targetDir → $real"
                }

                "link" -> {
                    if (Fs.exists(targetDir)) {
                        steps += "profile 已是指向桌面端的目录链接：$targetDir"
                    } else {
                        Links.deleteLink(targetDir)
                        Links.createJunction(targetDir, real)
                        steps += "已修复失效的目录链接 → $real"
                    }
                }

                "dir" -> return ProfileResult(
                    false, steps, "目标 profile 已存在且不是目录链接：$targetDir；请删除或改为链接后重试",
                )

                else -> return ProfileResult(false, steps, "目标路径是文件而不是目录：$targetDir")
            }
            ProfileResult(true, steps)
        } catch (e: Exception) {
            ProfileResult(false, steps, e.message ?: e.toString())
        }
    }

    /**
     * 确保来源 profile 目录可用。返回非 null 表示失败，调用方直接返回该结果。
     *
     * 来源必须是**真实目录**：缺失时按模板创建；断链先删链接再创建；
     * 普通文件不允许（`createDirectories` 会失败，这里给出明确错误）。
     */
    private fun ensureSource(sourceDir: Path, steps: MutableList<String>): ProfileResult? {
        when (Links.inspect(sourceDir).kind) {
            "missing" -> {
                val created = DshProfileBootstrap.ensureAt(sourceDir)
                    ?: return ProfileResult(false, steps, "无法创建来源 profile：$sourceDir")
                steps += "来源 profile 不存在，已按模板创建：$created"
            }

            "link" -> {
                if (!Fs.exists(sourceDir)) {
                    Links.deleteLink(sourceDir)
                    val created = DshProfileBootstrap.ensureAt(sourceDir)
                        ?: return ProfileResult(false, steps, "无法创建来源 profile：$sourceDir")
                    steps += "来源链接失效，已改为独立目录：$created"
                } else if (!Files.isDirectory(sourceDir)) {
                    return ProfileResult(false, steps, "来源 profile 不是目录：$sourceDir")
                } else {
                    fillMissingTemplate(sourceDir, steps)
                }
            }

            "dir" -> fillMissingTemplate(sourceDir, steps)

            else -> return ProfileResult(false, steps, "来源 profile 是文件而不是目录：$sourceDir")
        }
        return null
    }

    /** 目录已存在但缺 `package.json` 等模板文件时补齐；只写缺失文件，不覆盖用户内容。 */
    private fun fillMissingTemplate(sourceDir: Path, steps: MutableList<String>) {
        if (DshProfileBootstrap.ensureAt(sourceDir) == null) {
            steps += "来源 profile 模板检查失败（继续尝试链接）：$sourceDir"
        }
    }
}
