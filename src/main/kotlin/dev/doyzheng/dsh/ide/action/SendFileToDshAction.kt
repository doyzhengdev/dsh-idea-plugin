package dev.doyzheng.dsh.ide.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import dev.doyzheng.dsh.ide.DshComposerMessage
import dev.doyzheng.dsh.ide.DshIdeService
import dev.doyzheng.dsh.ide.icons.DshIcons

/**
 * 项目树 / 编辑器 Tab / 编辑器右键：把选中的文件或目录添加为 DSH 对话里的 `@引用`。
 *
 * 只发路径引用，不读文件内容——与官方 `@` 选择器的行为一致，模型按路径自己读。
 * 文案写在构造器里（不走 message bundle，避免 XML 里的 `%key%` 被当占位符）。
 */
class SendFileToDshAction : AnAction(
    "添加到 DSH 对话",
    "把选中的文件或目录路径作为 @引用 添加到 DSH 对话",
    DshIcons.ToolWindow,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolveFiles(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = resolveFiles(e)
        if (files.isEmpty()) return
        DshIdeService.getInstance(project).sendToComposer(
            files.map { DshComposerMessage.FileRef(path = it.path, isDirectory = it.isDirectory) },
        )
    }

    /** 个别菜单语境只供给单数 VIRTUAL_FILE、无数组；补单数兜底，避免入口被隐藏。 */
    private fun resolveFiles(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.filter(::isSupported)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf(::isSupported)?.let { listOf(it) }
            ?: emptyList()

    /** 远程/内存文件的路径对 DSH 没有意义，只接受本机文件系统。 */
    private fun isSupported(file: VirtualFile): Boolean = file.isValid && file.isInLocalFileSystem
}
