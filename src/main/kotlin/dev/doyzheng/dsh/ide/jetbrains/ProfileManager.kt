package dev.doyzheng.dsh.ide.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.doyzheng.dsh.ide.DshBundleBootstrap
import dev.doyzheng.dsh.ide.DshProfileBootstrap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class ProfileInfo(
    val name: String,
    val path: String,
    val kind: String,
    val target: String?,
    val packageName: String?,
    val hasIdePlugin: Boolean,
)

data class BackupInfo(val name: String, val path: String)

data class ProfileResult(
    val ok: Boolean,
    val steps: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * DSH profile 管理（本机文件操作，不需要 DSH 运行）。
 *
 * 目标 profile 名来自 IDEA 设置 ([dev.doyzheng.dsh.ide.DshSettings.Settings.profile]，默认 `idea`）。
 * 三种方式与旧 JS 设置页一致：
 *  - reuse：把目标做成指向来源（desktop/web）的目录链接（junction）；
 *  - copy：从来源选择性复制若干条目到目标；
 *  - empty：建一个带默认模板的空 profile。
 * 每次替换前自动备份；可切回。应用后**强制**确保目标装有 `dsh-plugin-jetbrains`。
 */
object ProfileManager {

    private const val IDE_BUNDLE_NAME = "dsh-plugin-jetbrains"

    val COPYABLE_ITEMS = listOf(
        "package.json", "cordis.patch.yml", "pnpm-workspace.yaml",
        "pnpm-lock.yaml", "node_modules", ".plugin-manager", "cache",
    )

    fun profilesRoot(): Path = DshProfileBootstrap.dshHome().resolve("profiles")

    private fun stateRoot(): Path = DshProfileBootstrap.dshHome().resolve("dsh-ide")

    fun listProfiles(): List<ProfileInfo> {
        val root = profilesRoot()
        if (!Files.isDirectory(root)) return emptyList()
        val entries = runCatching { Files.newDirectoryStream(root).use { it.toList() } }.getOrDefault(emptyList())
        return entries.map { dir ->
            val info = Links.inspect(dir)
            val manifest = readJson(dir.resolve("package.json"))
            ProfileInfo(
                name = dir.fileName.toString(),
                path = dir.toString(),
                kind = info.kind,
                target = info.target,
                packageName = manifest?.get("name")?.let { if (it.isJsonPrimitive) it.asString else null },
                hasIdePlugin = Fs.exists(dir.resolve("node_modules").resolve(IDE_BUNDLE_NAME)),
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun listBackups(target: String): List<BackupInfo> {
        val root = stateRoot().resolve("profile-backups")
        val names = runCatching { Files.newDirectoryStream(root).use { s -> s.map { it.fileName.toString() } } }.getOrDefault(emptyList())
        return names.filter { it.startsWith("$target-") }
            .sortedByDescending { it }
            .map { BackupInfo(it, root.resolve(it).toString()) }
    }

    fun apply(target: String, mode: String, source: String?, items: List<String>?): ProfileResult {
        if (mode !in setOf("reuse", "copy", "empty")) return ProfileResult(false, error = "未知 mode: $mode")
        val root = profilesRoot()
        Files.createDirectories(root)
        val targetDir = root.resolve(target)
        val steps = mutableListOf<String>()

        var sourceDir: Path? = null
        if (mode == "reuse" || mode == "copy") {
            if (source.isNullOrBlank()) return ProfileResult(false, steps, "reuse/copy 需要 source（desktop 或 web）")
            sourceDir = root.resolve(source.trim())
            if (!Fs.exists(sourceDir)) return ProfileResult(false, steps, "源 profile 不存在：$sourceDir")
        }

        // 备份现有目标
        val backupDir = stateRoot().resolve("profile-backups").resolve("$target-${Fs.stamp()}")
        val current = Links.inspect(targetDir)
        when (current.kind) {
            "missing" -> {}
            "link" -> {
                Files.createDirectories(backupDir)
                Fs.writeText(backupDir.resolve("link.txt"), current.target ?: "")
                Links.deleteLink(targetDir)
                steps += "已记录原有目录链接 → ${current.target ?: "?"}"
            }
            "dir" -> {
                Files.createDirectories(backupDir)
                Files.move(targetDir, backupDir.resolve("profile"))
                steps += "已备份原 profile 到 $backupDir"
            }
            else -> return ProfileResult(false, steps, "目标路径是文件而不是目录：$targetDir")
        }

        try {
            when (mode) {
                "reuse" -> {
                    val real = sourceDir!!.toRealPath()
                    Links.createJunction(targetDir, real)
                    steps += "已创建目录链接 $targetDir → $real"
                }
                "copy" -> {
                    Files.createDirectories(targetDir)
                    val chosen = if (!items.isNullOrEmpty()) items.filter { it in COPYABLE_ITEMS } else COPYABLE_ITEMS
                    for (item in chosen) {
                        val from = sourceDir!!.resolve(item)
                        if (!Fs.exists(from)) continue
                        Fs.copyTree(from, targetDir.resolve(item))
                        steps += "已复制 $item"
                    }
                }
                "empty" -> {
                    Files.createDirectories(targetDir)
                    DshProfileBootstrap.ensureAt(targetDir)
                    steps += "已创建空 profile（含默认 package.json / cordis.patch.yml / pnpm-workspace.yaml）"
                }
            }
        } catch (e: Exception) {
            return ProfileResult(false, steps, e.message ?: e.toString())
        }

        // 无论哪种方式，都保证目标装有 dsh-plugin-jetbrains。
        try {
            val ensured = DshBundleBootstrap.ensureInstalled(targetDir)
            steps += if (ensured) "已在 package.json 登记 $IDE_BUNDLE_NAME 并同步插件文件" else "已确认 $IDE_BUNDLE_NAME 就绪"
            return ProfileResult(true, steps)
        } catch (e: Exception) {
            return ProfileResult(false, steps, "应用 profile 后确保 $IDE_BUNDLE_NAME 失败：${e.message}")
        }
    }

    fun restore(target: String, backupPath: String?): ProfileResult {
        val root = profilesRoot()
        val targetDir = root.resolve(target)
        var backup = backupPath?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        if (backup == null) {
            backup = listBackups(target).firstOrNull()?.let { Paths.get(it.path) }
                ?: return ProfileResult(false, error = "没有可恢复的备份（$target）")
        }
        if (!Fs.exists(backup)) return ProfileResult(false, error = "备份不存在：$backup")

        val steps = mutableListOf<String>()
        val current = Links.inspect(targetDir)
        when (current.kind) {
            "link" -> { Links.deleteLink(targetDir); steps += "已删除当前目录链接" }
            "dir" -> {
                val safety = stateRoot().resolve("profile-backups").resolve("$target-pre-restore-${Fs.stamp()}")
                Files.move(targetDir, safety)
                steps += "当前 profile 已移到 $safety"
            }
            "file" -> return ProfileResult(false, steps, "目标路径是文件而不是目录：$targetDir")
            else -> {}
        }

        val linkTarget = Fs.readText(backup.resolve("link.txt"))?.trim()
        if (!linkTarget.isNullOrEmpty()) {
            Links.createJunction(targetDir, Paths.get(linkTarget))
            steps += "已恢复目录链接 → $linkTarget"
        } else {
            val stored = backup.resolve("profile")
            if (!Fs.exists(stored)) return ProfileResult(false, steps, "备份内容缺失：$stored")
            Files.createDirectories(root)
            Fs.copyTree(stored, targetDir)
            steps += "已从备份恢复 profile 目录"
        }

        try {
            val ensured = DshBundleBootstrap.ensureInstalled(targetDir)
            steps += if (ensured) "已在 package.json 登记 $IDE_BUNDLE_NAME" else "已确认 $IDE_BUNDLE_NAME 就绪"
            return ProfileResult(true, steps)
        } catch (e: Exception) {
            return ProfileResult(false, steps, "恢复后确保 $IDE_BUNDLE_NAME 失败：${e.message}")
        }
    }

    private fun readJson(path: Path): JsonObject? {
        val text = Fs.readText(path) ?: return null
        return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }
}
