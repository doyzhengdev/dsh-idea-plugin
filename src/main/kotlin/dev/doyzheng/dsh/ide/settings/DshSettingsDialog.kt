package dev.doyzheng.dsh.ide.settings

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import dev.doyzheng.dsh.ide.DshBundleBootstrap
import dev.doyzheng.dsh.ide.DshProfileBootstrap
import dev.doyzheng.dsh.ide.DshServerService
import dev.doyzheng.dsh.ide.DshSettings
import dev.doyzheng.dsh.ide.client.DshClientProvisioner
import dev.doyzheng.dsh.ide.client.DshDesktopClient
import dev.doyzheng.dsh.ide.jetbrains.Links
import dev.doyzheng.dsh.ide.jetbrains.ProfileManager
import dev.doyzheng.dsh.ide.ui.HtmlPages
import java.awt.Desktop
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * DSH 设置弹窗（HTML）。
 *
 * 内容由 JCEF 渲染 `dsh-ide/settings.html`，样式与状态更新都走页面；Kotlin 只负责数据与动作：
 *  - Kotlin → 页面：`window.__dshIdeSettingsUpdate(state)`；
 *  - 页面 → Kotlin：`window.__dshIdeSettingsAction(action)`（repair / restart / open-profile / open-client）。
 *
 * 所有 I/O（定位客户端、inspect profile 链接、一键修复）都在后台线程执行，结果通过状态对象回推。
 */
class DshSettingsDialog(private val project: Project?) : DialogWrapper(project) {

    private val settings: DshSettings.Settings get() = DshSettings.getInstance().current
    private val gson = Gson()

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val actionQuery: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it) }
    private var loadHandler: CefLoadHandlerAdapter? = null

    @Volatile
    private var pageReady = false

    @Volatile
    private var currentState: ViewState = ViewState(
        host = settings.host.ifBlank { "127.0.0.1" },
        port = settings.port,
        profile = settings.profile.ifBlank { DshSettings.DEFAULT_PROFILE },
        profilePath = "",
        profileLink = null,
        clientDir = null,
        clientVersion = null,
        nodeVersion = null,
        clientKnown = false,
        busy = false,
        message = "就绪",
        messageKind = "info",
    )

    init {
        title = "DSH 设置"
        actionQuery?.addHandler { action ->
            handleAction(action)
            null
        }
        browser?.let { target ->
            val handler = object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true && cefBrowser != null) {
                        actionQuery?.let {
                            cefBrowser.executeJavaScript(
                                "window.__dshIdeSettingsAction = function(action) { ${it.inject("action")} };",
                                cefBrowser.url,
                                0,
                            )
                        }
                        pageReady = true
                        pushState(currentState)
                    }
                }
            }
            target.jbCefClient.addLoadHandler(handler, target.cefBrowser)
            loadHandler = handler
            target.loadHTML(settingsPage())
        }
        init()
        cancelAction.putValue(Action.NAME, "关闭")
        setSize(600, 470)
        if (browser != null) refreshAll("就绪", "info")
    }

    override fun getPreferredSize(): Dimension = Dimension(600, 470)

    override fun createCenterPanel(): JComponent =
        browser?.component ?: JBLabel("当前 IDE 不支持 JCEF，无法显示设置页面。")

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun dispose() {
        loadHandler?.let { handler -> browser?.let { runCatching { it.jbCefClient.removeLoadHandler(handler, it.cefBrowser) } } }
        loadHandler = null
        actionQuery?.dispose()
        browser?.dispose()
        super.dispose()
    }

    // ---------------------------------------------------------------------------------------------
    // 页面 ↔ Kotlin
    // ---------------------------------------------------------------------------------------------

    private fun handleAction(action: String) {
        when (action) {
            "repair" -> repairAll()
            "restart" -> restartService()
            "open-profile" -> openDirectory(currentState.profilePath)
            "open-client" -> currentState.clientDir?.let(::openDirectory)
        }
    }

    private fun pushState(state: ViewState) {
        currentState = state
        if (!pageReady) return
        val json = gson.toJson(state)
        val target = browser ?: return
        SwingUtilities.invokeLater {
            runCatching {
                target.cefBrowser.executeJavaScript(
                    "window.__dshIdeSettingsUpdate && window.__dshIdeSettingsUpdate($json);",
                    target.cefBrowser.url,
                    0,
                )
            }
        }
    }

    private fun updateStatus(message: String, kind: String = "info", busy: Boolean = currentState.busy) {
        pushState(currentState.copy(message = message, messageKind = kind, busy = busy))
    }

    // ---------------------------------------------------------------------------------------------
    // 动作
    // ---------------------------------------------------------------------------------------------

    private fun repairAll() {
        if (currentState.busy || !confirmRepair()) return
        pushState(currentState.copy(busy = true, message = "正在一键修复…", messageKind = "info"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val service = DshServerService.getInstance()
                service.stopBlocking()
                val client = DshClientProvisioner.ensure { message ->
                    pushState(currentState.copy(message = message, messageKind = "info", busy = true))
                }
                val profile = settings.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
                val linked = ProfileManager.ensureLinked(profile)
                if (!linked.ok) error(linked.error ?: "profile 修复失败")
                DshBundleBootstrap.install(DshProfileBootstrap.profileDir(profile))
                service.startAsync()
                "一键修复完成（客户端 ${client.version ?: "?"}，Node ${client.nodeVersion ?: "?"}）"
            }
            result.onSuccess { refreshAll(it, "success") }
                .onFailure { refreshAll("操作失败：${it.message ?: it}", "error") }
        }
    }

    /**
     * 一键修复会停止服务、重装客户端并重建 profile，属于耗时且会打断当前会话的操作，
     * 先弹窗确认再执行。对话框可能从 JCEF 线程触发，必须切到 EDT 上弹。
     */
    private fun confirmRepair(): Boolean {
        val ask = {
            Messages.showYesNoDialog(
                project,
                "一键修复会停止当前 DSH 服务，重新检测或安装官方客户端，并重建 profile 链接，可能需要几分钟。\n\n是否继续？",
                "一键修复",
                "开始修复",
                "取消",
                Messages.getQuestionIcon(),
            ) == Messages.YES
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return ask()
        var confirmed = false
        application.invokeAndWait { confirmed = ask() }
        return confirmed
    }

    private fun restartService() {
        DshServerService.getInstance().restartAsync()
        // 重启在后台进行，弹窗不再需要停留在前台。
        SwingUtilities.invokeLater { close(CANCEL_EXIT_CODE) }
    }

    private fun openDirectory(rawPath: String?) {
        if (rawPath.isNullOrBlank()) return
        val directory = runCatching { Path.of(rawPath) }.getOrNull()
        if (directory == null || !Files.isDirectory(directory)) {
            updateStatus("目录不存在：$rawPath", "error", busy = false)
            return
        }
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile())
            } else {
                val command = when {
                    SystemInfo.isWindows -> listOf("explorer.exe", directory.toString())
                    SystemInfo.isMac -> listOf("open", directory.toString())
                    else -> listOf("xdg-open", directory.toString())
                }
                ProcessBuilder(command).start()
            }
        }.onFailure { updateStatus("无法打开目录：${it.message ?: it}", "error", busy = false) }
    }

    // ---------------------------------------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------------------------------------

    /** 后台重建完整状态（profile 链接、客户端版本），完成后推给页面。 */
    private fun refreshAll(message: String, kind: String) {
        pushState(currentState.copy(busy = true, message = "正在读取环境…", messageKind = "info"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val profile = settings.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
            val profilePath = DshProfileBootstrap.profileDir(profile).toAbsolutePath().normalize().toString()
            val info = runCatching { Links.inspect(Path.of(profilePath)) }.getOrNull()
            val link = info?.takeIf { it.kind == "link" }?.target
            val client = runCatching { DshDesktopClient.locate() }.getOrNull()
            pushState(
                currentState.copy(
                    host = settings.host.ifBlank { "127.0.0.1" },
                    port = settings.port,
                    profile = profile,
                    profilePath = profilePath,
                    profileLink = link,
                    clientDir = client?.installDir?.toString(),
                    clientVersion = client?.version,
                    nodeVersion = client?.nodeVersion,
                    clientKnown = true,
                    busy = false,
                    message = message,
                    messageKind = kind,
                ),
            )
        }
    }

    private data class ViewState(
        val host: String,
        val port: Int,
        val profile: String,
        val profilePath: String,
        val profileLink: String?,
        val clientDir: String?,
        val clientVersion: String?,
        val nodeVersion: String?,
        val clientKnown: Boolean,
        val busy: Boolean,
        val message: String,
        val messageKind: String,
    )

    // ---------------------------------------------------------------------------------------------
    // HTML
    // ---------------------------------------------------------------------------------------------

    private fun settingsPage(): String =
        HtmlPages.render("settings.html", mapOf("THEME" to if (JBColor.isBright()) "light" else "dark"))
}
