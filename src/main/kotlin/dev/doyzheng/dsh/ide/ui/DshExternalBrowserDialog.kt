package dev.doyzheng.dsh.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * 系统默认浏览器不可用时的兜底：用内置 JCEF 打开外部链接。
 */
class DshExternalBrowserDialog(project: Project?, url: String) : DialogWrapper(project) {

    private val browser = JBCefBrowser()

    init {
        title = "外部链接"
        browser.loadURL(url)
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent = browser.component.apply {
        preferredSize = Dimension(1000, 700)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun dispose() {
        runCatching { Disposer.dispose(browser) }
        super.dispose()
    }
}
