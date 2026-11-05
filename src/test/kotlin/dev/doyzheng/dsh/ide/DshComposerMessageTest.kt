package dev.doyzheng.dsh.ide

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `DshComposerMessage` 的 JSON 契约：页面侧（client.js）按这些字段拼 `@路径` 引用。
 */
class DshComposerMessageTest {

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `文件引用带 kind`() {
        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(DshComposerMessage.FileRef("E:/work/dsh-ide/README.md")),
            )!!,
        )

        assertEquals("fileRefs", message.get("op").asString)
        val ref = message.getAsJsonArray("refs").single().asJsonObject
        assertEquals("E:/work/dsh-ide/README.md", ref.get("path").asString)
        assertEquals("file", ref.get("kind").asString)
        assertFalse("整文件引用不带行号", ref.has("startLine"))
    }

    @Test
    fun `目录引用 kind 为 directory`() {
        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(DshComposerMessage.FileRef("E:/work/dsh-ide/src", isDirectory = true)),
            )!!,
        )

        val ref = message.getAsJsonArray("refs").single().asJsonObject
        assertEquals("directory", ref.get("kind").asString)
    }

    @Test
    fun `选区带 1-based 起止行`() {
        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(DshComposerMessage.FileRef("E:/a.kt", startLine = 12, endLine = 30)),
            )!!,
        )

        val ref = message.getAsJsonArray("refs").single().asJsonObject
        assertEquals(12, ref.get("startLine").asInt)
        assertEquals(30, ref.get("endLine").asInt)
    }

    @Test
    fun `endLine 小于 startLine 时收敛为单行`() {
        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(DshComposerMessage.FileRef("E:/a.kt", startLine = 7, endLine = 3)),
            )!!,
        )

        val ref = message.getAsJsonArray("refs").single().asJsonObject
        assertEquals(7, ref.get("startLine").asInt)
        assertEquals(7, ref.get("endLine").asInt)
    }

    @Test
    fun `空白路径被跳过，全部为空时返回 null`() {
        assertNull(DshComposerMessage.fileRefs(emptyList()))
        assertNull(DshComposerMessage.fileRefs(listOf(DshComposerMessage.FileRef("  "))))

        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(
                    DshComposerMessage.FileRef(""),
                    DshComposerMessage.FileRef("E:/a.kt"),
                ),
            )!!,
        )
        assertEquals(1, message.getAsJsonArray("refs").size())
    }

    @Test
    fun `多引用保持选择顺序`() {
        val message = parse(
            DshComposerMessage.fileRefs(
                listOf(
                    DshComposerMessage.FileRef("E:/a.kt"),
                    DshComposerMessage.FileRef("E:/dir", isDirectory = true),
                ),
            )!!,
        )

        val refs = message.getAsJsonArray("refs").map { it.asJsonObject.get("path").asString }
        assertEquals(listOf("E:/a.kt", "E:/dir"), refs)
    }
}
