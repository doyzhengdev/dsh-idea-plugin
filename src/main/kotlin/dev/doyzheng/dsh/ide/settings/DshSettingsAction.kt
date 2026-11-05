package dev.doyzheng.dsh.ide.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * DSH 工具窗口标题栏的齿轮动作。
 *
 * 通过 `ToolWindow.setTitleActions` 注册，显示在工具窗口右上角、三点内容菜单的左侧；
 * 点击打开独立的 [DshSettingsDialog]。
 */
class DshSettingsAction(private val project: Project) :
    AnAction("DSH 设置", "打开 DSH 设置弹窗", AllIcons.General.GearPlain) {

    override fun actionPerformed(event: AnActionEvent) {
        if (project.isDisposed) return
        DshSettingsDialog(project).show()
    }
}
