package dev.doyzheng.dsh.ide.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** 插件图标：工具窗口在 plugin.xml 中引用 DshIcons.ToolWindow。 */
object DshIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/dsh-toolwindow.svg", DshIcons::class.java)
}
