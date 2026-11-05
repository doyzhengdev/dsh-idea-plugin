package dev.doyzheng.dsh.ide

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 每次 IDE 启动时，把 **DSH 侧插件** `dsh-plugin-jetbrains` 注入到 IDE 正在使用的 profile。
 *
 * 为什么需要它：profile 可能是「引用 desktop/web 的 junction」或「复制 desktop/web 的真实目录」，
 * 来源 profile 未必装过 DSH 插件；若不注入，IDE 里的 DSH 会缺 ideWorkspace / 文件桥 / 设置页。
 *
 * 做法（与早年的 `dsh-ide-client` 注入同思路，但注入的是整个插件包）：
 *  1. 把本插件 jar 里打包的 `/dsh-plugin-jetbrains` 目录下的文件拷贝到
 *     `<profile>/node_modules/dsh-plugin-jetbrains`；
 *  2. 若该 profile 的 `dsh.profile.bundles` 已包含它（例如引用的是已装插件的 desktop），
 *     就交给正常 bundle 加载，**不再** `--patch`（避免重复 id）；
 *  3. 否则写一个 `<profile>/dsh-plugin-jetbrains.patch.yml` 覆盖层，由启动命令 `--patch` 挂载。
 *
 * 幂等；失败只记日志并返回 null，不阻止 dsh 启动（最坏退化成没有 IDE 桥）。
 */
object DshBundleBootstrap {

    private const val PACKAGE_NAME = "dsh-plugin-jetbrains"
    private const val RESOURCE_ROOT = "/dsh-plugin-jetbrains"
    /** 历史包名：ensureInstalled 时从 profile 的 dependencies/bundles 里清掉。 */
    private val LEGACY_BUNDLE_NAMES = listOf("dsh-jet-brains-idea", "dsh-plugin-jet-brains")
    private val PACKAGE_FILES = listOf(
        "package.json",
        "cordis.patch.yml",
        "src/index.js",
        "src/client.js",
        "locale/zh.json",
        "locale/en.json",
        "icon.svg",
    )

    const val OVERLAY_FILE_NAME = "dsh-plugin-jetbrains.patch.yml"

    private val OVERLAY_CONTENT = """
        # DSH for JetBrains 插件自动维护：把 DSH 侧插件注入本 profile，请勿手改本文件。
        - insert:
            - id: $PACKAGE_NAME
              name: $PACKAGE_NAME
    """.trimIndent() + "\n"

    /**
     * @param overlay 非空表示启动命令要加 `--patch <该文件>`；null 表示 profile 已通过 bundles 加载。
     */
    data class Plan(val overlay: Path?, val viaBundle: Boolean)

    fun install(profileDir: Path): Plan? = runCatching {
        ensurePackageFiles(profileDir)
        if (profileHasBundle(profileDir)) {
            return@runCatching Plan(null, viaBundle = true)
        }
        val overlay = profileDir.resolve(OVERLAY_FILE_NAME)
        writeIfChanged(overlay, OVERLAY_CONTENT.toByteArray(Charsets.UTF_8))
        Plan(overlay, viaBundle = false)
    }.onFailure {
        thisLogger().warn("DSH 侧插件注入失败（IDE 桥可能不可用）: $profileDir", it)
    }.getOrNull()

    /** 拷贝插件文件到 `node_modules/dsh-plugin-jetbrains`；若已是有效目录链接则跳过。 */
    private fun ensurePackageFiles(profileDir: Path) {
        val target = profileDir.resolve("node_modules").resolve(PACKAGE_NAME)
        if (Files.isSymbolicLink(target)) {
            if (Files.exists(target)) return
            // 断链：删掉重新拷
            runCatching { Files.delete(target) }
        }
        PACKAGE_FILES.forEach { rel ->
            val destination = target.resolve(rel)
            destination.parent?.let { Files.createDirectories(it) }
            val bytes = DshBundleBootstrap::class.java.getResourceAsStream("$RESOURCE_ROOT/$rel")?.use { it.readBytes() }
                ?: error("插件资源缺失: $RESOURCE_ROOT/$rel")
            writeIfChanged(destination, bytes)
        }
    }

    /**
     * 把 DSH 侧插件**永久**登记进 profile（供 ProfileManager apply/restore 调用）：
     * 拷贝插件文件到 `node_modules/<pkg>`，并在 `package.json` 写入 `dependencies`（link: 指向该目录）
     * 与 `dsh.profile.bundles`。返回是否发生了改动；失败抛异常由调用方转成错误信息。
     */
    fun ensureInstalled(profileDir: Path): Boolean {
        ensurePackageFiles(profileDir)
        val manifest = profileDir.resolve("package.json")
        val pluginDir = profileDir.resolve("node_modules").resolve(PACKAGE_NAME)
        val real = runCatching { pluginDir.toRealPath().toString() }
            .getOrElse { pluginDir.toAbsolutePath().normalize().toString() }
        val linkValue = "link:" + real.replace('\\', '/')

        val root: JsonObject = runCatching {
            if (Files.exists(manifest)) JsonParser.parseString(Files.readString(manifest, Charsets.UTF_8)).asJsonObject else null
        }.getOrNull() ?: JsonObject().apply {
            addProperty("name", "dsh-profile-${profileDir.fileName}")
            addProperty("private", true)
            add("dependencies", JsonObject())
            add("dsh", JsonObject().apply { add("profile", JsonObject().apply { add("bundles", JsonArray()) }) })
        }
        var changed = false

        val deps = root.getAsJsonObject("dependencies") ?: JsonObject().also { root.add("dependencies", it) }
        if (deps.get(PACKAGE_NAME)?.takeIf { it.isJsonPrimitive }?.asString != linkValue) {
            deps.addProperty(PACKAGE_NAME, linkValue)
            changed = true
        }
        for (legacy in LEGACY_BUNDLE_NAMES) {
            if (deps.has(legacy)) { deps.remove(legacy); changed = true }
        }

        val dsh = root.getAsJsonObject("dsh") ?: JsonObject().also { root.add("dsh", it) }
        val profile = dsh.getAsJsonObject("profile") ?: JsonObject().also { dsh.add("profile", it) }
        val bundles = profile.getAsJsonArray("bundles") ?: JsonArray().also { profile.add("bundles", it) }
        val original = bundles.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
        val desired = original.filter { it !in LEGACY_BUNDLE_NAMES }.toMutableList()
        if (PACKAGE_NAME !in desired) desired.add(PACKAGE_NAME)
        if (desired != original) {
            val rebuilt = JsonArray()
            desired.forEach { rebuilt.add(it) }
            profile.add("bundles", rebuilt)
            changed = true
        }

        if (changed) {
            val text = GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
            Files.writeString(manifest, text, Charsets.UTF_8)
        }
        return changed
    }

    /** package.json 的 bundles 是否已包含 dsh-plugin-jetbrains（轻量文本判断，避免引入 JSON 库）。 */
    private fun profileHasBundle(profileDir: Path): Boolean {
        val manifest = profileDir.resolve("package.json")
        if (!Files.exists(manifest)) return false
        val text = runCatching { Files.readString(manifest, Charsets.UTF_8) }.getOrNull() ?: return false
        return text.contains("\"$PACKAGE_NAME\"")
    }

    private fun writeIfChanged(target: Path, bytes: ByteArray) {
        if (Files.exists(target) &&
            Files.size(target) == bytes.size.toLong() &&
            Files.readAllBytes(target).contentEquals(bytes)
        ) {
            return
        }
        Files.write(target, bytes)
    }
}
