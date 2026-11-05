package dev.doyzheng.dsh.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 项目打开后自动展示 DSH 工具窗口。
 *
 * 为什么需要它：服务是懒加载的（`DshPanel.onShown()` 才触发启动），
 * 如果工具窗口不显示，插件就等于没生效。打开窗口这一步不该依赖用户手动点菜单。
 *
 * 是否自动展示窗口由 `DshSettings.autoStart` 决定。注意它**只**控制"项目打开时自动展示窗口"，
 * 不控制服务启停：工具窗口 content 创建时会调用 `DshPanel.onShown()`（普通显示/隐藏不会重复触发），
 * 服务只在 `STOPPED`/`FAILED` 状态下由该回调启动；关掉 autoStart 的用户手动首次打开窗口仍会启动服务。
 */
class DshStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = DshSettings.getInstance().current
        if (!settings.autoStart) return
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("DSH")?.show()
        }
    }
}
