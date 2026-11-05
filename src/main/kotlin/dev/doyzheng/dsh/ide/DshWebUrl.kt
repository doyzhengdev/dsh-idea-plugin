package dev.doyzheng.dsh.ide

import java.net.URLEncoder

/**
 * 生成带 `ideWorkspace` 的 DSH 页面 URL。
 *
 * 为什么放在 fragment 而不是 query：带 token 首次打开时，DSH 用 303 `Location: ./`
 * 换 cookie，浏览器会丢弃响应 Location 里的 query（`ideWorkspace` 一起丢），
 * 而 RFC 7231 规定 3xx 的 Location 不含 fragment 时浏览器继承原 fragment。
 * DSH 客户端没有 location.hash 路由，所以参数放在 fragment 里不会干扰它。
 */
object DshWebUrl {

    const val WORKSPACE_PARAM = "ideWorkspace"

    /**
     * 把 `projectPath` 作为 fragment 参数追加到 [base]；路径为空时原样返回 [base]。
     *
     * 编码用 [URLEncoder]，它按表单规则把空格编成 `+`，这里再换成 `%20`，
     * 避免其他消费者按 URL 语义把 `+` 当成字面加号。路径里的 `+` 本身会被编码成 `%2B`，
     * 因此这个替换不会误伤真实字符。
     */
    fun workspaceUrl(base: String, projectPath: String?): String {
        val path = projectPath ?: return base
        if (path.isBlank()) return base
        val withoutFragment = base.substringBefore('#')
        val encoded = URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
        return "$withoutFragment#$WORKSPACE_PARAM=$encoded"
    }
}
