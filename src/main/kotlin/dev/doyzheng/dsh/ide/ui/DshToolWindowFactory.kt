package dev.doyzheng.dsh.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 工具窗口工厂。每个项目窗口一个 JCEF 面板，但共享应用级唯一的 dsh 进程。
 */
class DshToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DshPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        panel.onShown()
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
