package dev.doyzheng.dsh.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import dev.doyzheng.dsh.ide.ui.DshStartupGate

/**
 * 项目启动完成后在后台启动宿主，但不展示工具窗口。
 *
 * 启动耗时已压到秒级，宿主可以随 IDE 启动预热；窗口保持隐藏，用户点击 DSH 图标才创建面板
 * （此时通常已经 RUNNING，直接加载页面）。平台恢复上次布局时可能把窗口恢复为可见，这里统一
 * 隐藏一次；先置位 [DshStartupGate] 再启动，启动阶段平台恢复布局触发的显示不算用户操作，
 * [DshServerService.startAsync] 本身也是幂等的。
 */
class DshStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DSH")
            if (toolWindow?.isVisible == true) toolWindow.hide()
            DshStartupGate.settle(project)
            DshServerService.getInstance().startAsync()
        }
    }
}
