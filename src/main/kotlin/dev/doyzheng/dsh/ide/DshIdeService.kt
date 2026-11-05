package dev.doyzheng.dsh.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.doyzheng.dsh.ide.ui.DshPanel

/**
 * 项目级服务：把 IDE 的「添加到 DSH 对话」入口接到当前项目的 DSH 面板上。
 *
 * DSH 进程是全 IDE 共享的应用级单例（[DshServerService]），但对话面板是每个项目窗口
 * 一个；发送的目标始终是「这个项目的 DSH 窗口」，所以这里按项目持有面板引用。
 *
 * 面板可能尚未创建（用户没打开过工具窗口）或页面尚未加载完成：消息先入队，
 * 面板挂载后由 [DshPanel.pushToWebview] 继续排队，直到页面 `onLoadEnd` 冲刷。
 */
@Service(Service.Level.PROJECT)
class DshIdeService(private val project: Project) : Disposable {

    /** 当前项目的面板；工具窗口 content 重建时会替换。 */
    @Volatile
    private var panel: DshPanel? = null

    /** 面板还不存在时暂存的消息；连续多次右键不能互相覆盖。 */
    private val queued = ArrayDeque<String>()

    internal fun attachPanel(panel: DshPanel) {
        this.panel = panel
        // 面板内部自己会在页面加载完成前排队，所以这里可以立即转交。
        val pending = synchronized(queued) {
            queued.toList().also { queued.clear() }
        }
        pending.forEach(panel::pushToWebview)
    }

    internal fun detachPanel(panel: DshPanel) {
        if (this.panel === panel) this.panel = null
    }

    /**
     * 把文件/目录引用发送到当前对话。
     * 工具窗口未打开时会先展示它（首次展示会创建面板并启动 DSH）。
     */
    fun sendToComposer(refs: List<DshComposerMessage.FileRef>) {
        val message = DshComposerMessage.fileRefs(refs) ?: return
        deliver(message)
    }

    /** 编辑器选区：只带文件路径与起止行，不带选中原文。 */
    fun sendSelectionToComposer(path: String, startLine: Int, endLine: Int) {
        sendToComposer(
            listOf(DshComposerMessage.FileRef(path = path, isDirectory = false, startLine = startLine, endLine = endLine)),
        )
    }

    private fun deliver(message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            if (toolWindow == null) {
                enqueue(message)
                return@invokeLater
            }
            // 面板可能还没创建（content 延迟初始化）：show 的回调在 content 创建之后执行。
            toolWindow.show {
                val current = panel
                if (current != null) current.pushToWebview(message) else enqueue(message)
            }
        }
    }

    private fun enqueue(message: String) {
        synchronized(queued) { queued.addLast(message) }
    }

    override fun dispose() {
        panel = null
        synchronized(queued) { queued.clear() }
    }

    companion object {
        /** 与 plugin.xml 的 `<toolWindow id="DSH">` 一致。 */
        const val TOOL_WINDOW_ID = "DSH"

        fun getInstance(project: Project): DshIdeService = project.service()
    }
}
