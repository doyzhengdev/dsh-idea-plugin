package dev.doyzheng.dsh.ide

import com.intellij.ui.JBColor
import java.util.Locale

/**
 * 把 IDEA 的界面语言与明暗主题传给 DSH。
 *
 * 这里只做两件事：判断当前值、由 `DshServerService` 写入
 * `DSH_IDE_LOCALE` / `DSH_IDE_THEME` 环境变量。实际持久化在 DSH 侧完成：
 * `dsh-plugin-jetbrains` 的 Host 半边调用官方 `configEditor` 写入 profile，
 * 保留 YAML 注释与同级配置，并在 Loader 热重载锁内串行化。
 */
object DshProfileAppearance {

    internal fun detectIdeLanguage(): String {
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        return when {
            language.startsWith("zh") -> "zh"
            language in SUPPORTED_LANGUAGES -> language
            else -> "en"
        }
    }

    internal fun detectIdeTheme(): String = if (JBColor.isBright()) "light" else "dark"

    private val SUPPORTED_LANGUAGES = setOf("en", "ja", "es", "fr", "de", "ru", "pt")
}
