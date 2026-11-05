package dev.doyzheng.dsh.ide

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import dev.doyzheng.dsh.ide.jetbrains.Fs
import dev.doyzheng.dsh.ide.jetbrains.Links
import java.nio.file.Files
import java.nio.file.Path

/**
 * 把 DSH 侧插件 `dsh-plugin-jetbrains` 注入 IDE 正在使用的 profile。
 *
 * 布局：
 *  - 插件实体：`<profile>/.dsh-ide/dsh-plugin-jetbrains`（在 node_modules 之外）；
 *  - `<profile>/node_modules/dsh-plugin-jetbrains`：指向实体的目录链接；
 *  - `<profile>/package.json` 的 `devDependencies`：`link:` 依赖；
 *  - `<profile>/dsh-plugin-jetbrains.patch.yml`：启动命令 `--patch` 挂载的覆盖层。
 *
 * 为什么是 devDependencies + --patch：
 *  - pnpm 的 add/remove 会保留 devDependencies，不会把目录当 extraneous 挪进 `.ignored`；
 *  - 官方插件管理页与插件市场只读 `dependencies`，本插件因此不会出现在「已安装插件」里；
 *  - 覆盖层条目不在 profile patch 索引里，官方插件管理器将其视为不可寻址（只读）。
 *
 * 幂等；失败只记日志并返回 null，不阻止 dsh 启动（最坏退化成没有 IDE 桥）。
 */
object DshBundleBootstrap {

    private const val PACKAGE_NAME = "dsh-plugin-jetbrains"
    private const val RESOURCE_ROOT = "/dsh-plugin-jetbrains"
    private const val STABLE_DIR = ".dsh-ide/$PACKAGE_NAME"
    private const val DEPENDENCY_SPEC = "link:./$STABLE_DIR"

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

    /** 返回启动命令要挂载的 `--patch` 覆盖层；注入失败返回 null。 */
    fun install(profileDir: Path): Path? = runCatching {
        ensurePackageFiles(profileDir)
        ensureManifestDevDependency(profileDir)
        val overlay = profileDir.resolve(OVERLAY_FILE_NAME)
        writeIfChanged(overlay, OVERLAY_CONTENT.toByteArray(Charsets.UTF_8))
        overlay
    }.onFailure {
        thisLogger().warn("DSH 侧插件注入失败（IDE 桥可能不可用）: $profileDir", it)
    }.getOrNull()

    /** 把插件文件写到稳定目录，并让 `node_modules/<pkg>` 指向它。 */
    private fun ensurePackageFiles(profileDir: Path) {
        val stable = profileDir.resolve(STABLE_DIR)
        PACKAGE_FILES.forEach { rel ->
            val destination = stable.resolve(rel)
            destination.parent?.let { Files.createDirectories(it) }
            val bytes = DshBundleBootstrap::class.java.getResourceAsStream("$RESOURCE_ROOT/$rel")?.use { it.readBytes() }
                ?: error("插件资源缺失: $RESOURCE_ROOT/$rel")
            writeIfChanged(destination, bytes)
        }

        val target = profileDir.resolve("node_modules").resolve(PACKAGE_NAME)
        val linkedToStable = runCatching {
            Files.exists(target) && target.toRealPath() == stable.toRealPath()
        }.getOrDefault(false)
        if (linkedToStable) return

        // 该路径由本插件独占：残留的普通目录/文件/错误链接都清掉重建。
        when (Links.inspect(target).kind) {
            "link" -> Links.deleteLink(target)
            "dir" -> Fs.deleteRecursively(target)
            "file" -> Files.deleteIfExists(target)
        }
        Links.createJunction(target, stable)
    }

    /** 在 profile 的 `devDependencies` 里登记 link 依赖，pnpm 会把目录保留在 node_modules。 */
    private fun ensureManifestDevDependency(profileDir: Path) {
        val manifest = profileDir.resolve("package.json")
        if (!Files.exists(manifest)) return
        val root = JsonParser.parseString(Files.readString(manifest, Charsets.UTF_8)).asJsonObject
        val dev = root.getAsJsonObject("devDependencies")
            ?: JsonObject().also { root.add("devDependencies", it) }
        if (dev.get(PACKAGE_NAME)?.takeIf { it.isJsonPrimitive }?.asString == DEPENDENCY_SPEC) return
        dev.addProperty(PACKAGE_NAME, DEPENDENCY_SPEC)
        Files.writeString(manifest, GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n", Charsets.UTF_8)
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
