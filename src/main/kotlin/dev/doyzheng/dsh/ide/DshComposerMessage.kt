package dev.doyzheng.dsh.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * IDE → 页面的「发送到 DSH 对话」消息。
 *
 * 页面侧（`dsh-plugin-jetbrains/client.js` 的 `__dshIdeInsertRefs`）把消息转成输入框里的
 * `@路径` 引用：DSH 的输入框把路径形态的 `@...` 文本扫描成 chip，发送时再由
 * user-text 规则解析，所以这里只传路径、行号，不传文件内容——模型可以自己按行号读文件。
 *
 * 纯 JSON 拼接，不接触 IDE 对象，便于单测。
 */
object DshComposerMessage {

    const val OP_FILE_REFS = "fileRefs"

    /**
     * 一个文件/目录引用。
     *
     * @param path 绝对路径（用 `/` 分隔；VFS 的 `VirtualFile.path` 天然如此）
     * @param isDirectory 目录引用在页面侧以 `/` 结尾
     * @param startLine 1-based，`0` 表示整文件
     * @param endLine 1-based，小于 [startLine] 时按 [startLine] 处理
     */
    data class FileRef(
        val path: String,
        val isDirectory: Boolean = false,
        val startLine: Int = 0,
        val endLine: Int = 0,
    )

    /** 无有效引用时返回 null（调用方直接跳过发送）。 */
    fun fileRefs(refs: List<FileRef>): String? {
        val valid = refs.filter { it.path.isNotBlank() }
        if (valid.isEmpty()) return null
        val array = JsonArray()
        valid.forEach { ref ->
            array.add(JsonObject().apply {
                addProperty("path", ref.path)
                addProperty("kind", if (ref.isDirectory) "directory" else "file")
                if (ref.startLine > 0) {
                    addProperty("startLine", ref.startLine)
                    addProperty("endLine", maxOf(ref.endLine, ref.startLine))
                }
            })
        }
        return JsonObject().apply {
            addProperty("op", OP_FILE_REFS)
            add("refs", array)
        }.toString()
    }
}
