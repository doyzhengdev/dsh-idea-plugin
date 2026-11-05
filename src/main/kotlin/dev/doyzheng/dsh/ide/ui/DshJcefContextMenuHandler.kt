package dev.doyzheng.dsh.ide.ui

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.callback.CefRunContextMenuCallback
import org.cef.handler.CefContextMenuHandler
import java.util.Locale

/**
 * 把 JCEF 原生右键菜单翻成 IDE 当前语言。
 *
 * Chromium 的菜单语言由 CefSettings.locale 在进程启动时决定；如果 IDE 已经先启动了 JCEF，
 * 插件再改 CefSettings 往往来不及。这里直接在菜单模型生成前改 label，稳定生效。
 */
class DshJcefContextMenuHandler : CefContextMenuHandler {

    private val chinese = Locale.getDefault().language.lowercase(Locale.ROOT).startsWith("zh")

    private val zhLabels = mapOf(
        CefMenuModel.MenuId.MENU_ID_BACK to "后退",
        CefMenuModel.MenuId.MENU_ID_FORWARD to "前进",
        CefMenuModel.MenuId.MENU_ID_RELOAD to "重新加载",
        CefMenuModel.MenuId.MENU_ID_RELOAD_NOCACHE to "强制重新加载",
        CefMenuModel.MenuId.MENU_ID_STOPLOAD to "停止加载",
        CefMenuModel.MenuId.MENU_ID_UNDO to "撤销",
        CefMenuModel.MenuId.MENU_ID_REDO to "重做",
        CefMenuModel.MenuId.MENU_ID_CUT to "剪切",
        CefMenuModel.MenuId.MENU_ID_COPY to "复制",
        CefMenuModel.MenuId.MENU_ID_PASTE to "粘贴",
        CefMenuModel.MenuId.MENU_ID_DELETE to "删除",
        CefMenuModel.MenuId.MENU_ID_SELECT_ALL to "全选",
        CefMenuModel.MenuId.MENU_ID_FIND to "查找",
        CefMenuModel.MenuId.MENU_ID_PRINT to "打印",
        CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE to "查看源代码",
    )

    override fun onBeforeContextMenu(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        model: CefMenuModel?,
    ) {
        if (!chinese || model == null) return
        for (index in 0 until model.count) {
            val commandId = runCatching { model.getCommandIdAt(index) }.getOrNull() ?: continue
            val label = zhLabels[commandId] ?: continue
            runCatching { model.setLabelAt(index, label) }
        }
    }

    override fun runContextMenu(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        model: CefMenuModel?,
        callback: CefRunContextMenuCallback?,
    ): Boolean = false

    override fun onContextMenuCommand(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        commandId: Int,
        eventFlags: Int,
    ): Boolean = false

    override fun onContextMenuDismissed(browser: CefBrowser?, frame: CefFrame?) {}
}
