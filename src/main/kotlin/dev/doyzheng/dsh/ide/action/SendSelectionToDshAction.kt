package dev.doyzheng.dsh.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.doyzheng.dsh.ide.DshIdeService
import dev.doyzheng.dsh.ide.icons.DshIcons

/**
 * 编辑器右键：把当前选区的文件路径与起止行添加到 DSH 对话。
 *
 * **只发引用，不发选中原文**：模型可以按行号自己读文件，避免把大段代码重复塞进 prompt。
 */
class SendSelectionToDshAction : AnAction(
    "添加选区到 DSH 对话",
    "把当前选区的文件路径与行号添加到 DSH 对话",
    DshIcons.ToolWindow,
) {

    // 选区信息要读 Document；在 BGT 上访问需要显式 read action，直接放 EDT 更简单也足够轻。
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val available = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE)?.isInLocalFileSystem == true &&
            e.getData(CommonDataKeys.EDITOR_EVEN_IF_INACTIVE) != null
        // 常驻可见、无选区置灰：只在有选区时才出现的话，用户根本发现不了这个入口。
        e.presentation.isVisible = available
        e.presentation.isEnabled = available && resolveSelection(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = resolveSelection(e) ?: return
        DshIdeService.getInstance(project).sendSelectionToComposer(
            selection.path,
            selection.startLine,
            selection.endLine,
        )
    }

    private data class Selection(
        val path: String,
        val startLine: Int,
        val endLine: Int,
    )

    private fun resolveSelection(e: AnActionEvent): Selection? {
        val editor = e.getData(CommonDataKeys.EDITOR_EVEN_IF_INACTIVE) ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isValid || !file.isInLocalFileSystem) return null
        val model = editor.selectionModel
        if (!model.hasSelection()) return null

        val document = editor.document
        val endOffset = model.selectionEnd.coerceIn(0, document.textLength)
        val startLine = document.getLineNumber(model.selectionStart.coerceIn(0, endOffset)) + 1
        val endLine = document.getLineNumber(endOffset) + 1
        return Selection(file.path, startLine, endLine)
    }
}
