package dev.doyzheng.dsh.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import dev.doyzheng.dsh.ide.settings.DshSettingsAction

/**
 * 工具窗口工厂。每个项目窗口一个 JCEF 面板，但共享应用级唯一的 dsh 进程。
 *
 * 宿主由 [dev.doyzheng.dsh.ide.DshStartupActivity] 在项目启动完成后后台启动，工具窗口不自动展示；
 * 用户点击 DSH 图标时创建 content 并显示。启动阶段平台恢复布局触发的显示（[DshStartupGate] 未置位）
 * 不触发启动，避免与 IDE 启动争抢资源。
 */
class DshToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DshPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        // 标题栏右上角齿轮（三点菜单左侧）：打开独立设置弹窗。
        toolWindow.setTitleActions(listOf(DshSettingsAction(project)))
        // 启动阶段恢复布局也会创建内容并触发 shown，这些都不算用户操作。
        project.messageBus.connect(panel).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == TOOL_WINDOW_ID && DshStartupGate.isSettled(project)) {
                        panel.onShown()
                    }
                }
            },
        )
        if (DshStartupGate.isSettled(project)) {
            panel.onShown()
        } else {
            // 项目启动完成前用户就点开了窗口：放行时若窗口仍可见，补一次启动。
            // 启动阶段恢复出来的窗口会被 DshStartupActivity 隐藏，不会走到这里启动。
            DshStartupGate.subscribe(project, panel) {
                if (toolWindow.isVisible) panel.onShown()
            }
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    private companion object {
        const val TOOL_WINDOW_ID = "DSH"
    }
}
