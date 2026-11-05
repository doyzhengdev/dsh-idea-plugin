package dev.doyzheng.dsh.ide.ui

/**
 * 内嵌 HTML 页面资源（`src/main/resources/dsh-ide/` 下的 html 文件）。
 *
 * 页面里的 `{{NAME}}` 占位符由 Kotlin 侧替换；变量值是否转义由调用方决定
 * （标题、说明等纯文本必须经过 [escape]，按钮等 HTML 片段则原样传入）。
 */
object HtmlPages {

    fun render(name: String, vars: Map<String, String> = emptyMap()): String {
        var html = read(name)
        vars.forEach { (key, value) -> html = html.replace("{{$key}}", value) }
        return html
    }

    private fun read(name: String): String {
        val stream = HtmlPages::class.java.getResourceAsStream("/dsh-ide/$name")
            ?: error("缺少 HTML 资源：/dsh-ide/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
