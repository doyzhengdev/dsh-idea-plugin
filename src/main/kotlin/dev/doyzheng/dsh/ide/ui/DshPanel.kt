package dev.doyzheng.dsh.ide.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import dev.doyzheng.dsh.ide.DshIdeService
import dev.doyzheng.dsh.ide.DshServerService
import dev.doyzheng.dsh.ide.DshState
import dev.doyzheng.dsh.ide.DshStatus
import dev.doyzheng.dsh.ide.DshWebUrl
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * 工具窗口内容：状态卡 + JCEF 浏览器。
 *
 * 每个 IDE 项目窗口都加载**同一个** dsh 端口，只在 URL 上带不同 `ideWorkspace`，
 * 这是「单进程多项目」的关键：同 origin 保证 localStorage 稳定，项目由 URL fragment 参数区分。
 *
 * `ideWorkspace` 由 DSH 插件 `dsh-plugin-jetbrains` 的 Client 半边（`client.js`）消费：
 * 页面加载后登记并选中该路径对应的 workspace，覆盖官方「最近项目」的默认恢复。
 */
class DshPanel(private val project: Project) : Disposable {

    private val service: DshServerService get() = DshServerService.getInstance()

    /** 当前项目的「发送到对话」路由；面板挂载/释放都经由它。 */
    private val ideService: DshIdeService = DshIdeService.getInstance(project)

    private val root = JPanel(BorderLayout())
    private val statusLabel = JBLabel("正在准备 DSH 运行时…")

    /** FAILED 后的产品内重试入口；默认隐藏，仅失败状态展示。平台无 `JBButton`，用 Swing 的 `JButton`。 */
    private val retryButton = JButton("重试").apply {
        isVisible = false
        addActionListener {
            // 不在 EDT 做 I/O：只调度异步启动；先隐藏按钮，随后的 STARTING 状态会刷新界面。
            setRetryVisible(false)
            service.startAsync()
        }
    }
    private val retryRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(8)
        isVisible = false
        add(retryButton)
    }
    private val statusPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12)
        add(statusLabel, BorderLayout.NORTH)
        add(retryRow, BorderLayout.SOUTH)
    }

    private var browser: JBCefBrowser? = null
    private var loadedUrl: String? = null

    /** DSH 页面 → IDE 的「打开文件」通道；页面里的 `window.__dshIdeOpenFile` 由它注入。 */
    private var openFileQuery: JBCefJSQuery? = null
    private var openFileLoadHandler: CefLoadHandlerAdapter? = null

    /** DSH 页面 → IDE 的「重启宿主 Web」通道；页面里的 `window.__dshIdeRestartWeb` 由它注入。 */
    private var restartWebQuery: JBCefJSQuery? = null

    /** IDE → 页面的「添加到对话」通道：页面加载完成前先排队，`onLoadEnd` 后冲刷。 */
    private val pendingPushes = ArrayDeque<String>()
    private var pageLoaded = false
    private val gson = Gson()
    private val log = thisLogger()

    private val onStatus: (DshStatus) -> Unit = { status ->
        SwingUtilities.invokeLater { render(status) }
    }

    val component: JPanel get() = root

    init {
        root.add(statusPanel, BorderLayout.CENTER)
        service.addListener(onStatus)
        render(service.currentStatus())
        ideService.attachPanel(this)
    }

    fun onShown() {
        // 只在工具窗口 content 创建时被调用（DshToolWindowFactory.createToolWindowContent），
        // 不是每次显示都触发：普通的显示/隐藏不会重跑这里。这里顺带覆盖 STOPPED/FAILED，
        // 让重建 content（重开项目/IDE）成为一次重试。
        val state = service.currentStatus().state
        if (state == DshState.STOPPED || state == DshState.FAILED) service.startAsync()
        render(service.currentStatus())
    }

    private fun render(status: DshStatus) {
        when (status.state) {
            DshState.RUNNING -> {
                val url = status.url ?: return
                setRetryVisible(false)
                root.remove(statusPanel)
                val target = browserUrl(url)
                val existing = browser
                if (existing == null) {
                    if (!JBCefApp.isSupported()) {
                        statusLabel.text = "当前 IDE 不支持 JCEF，无法内嵌 DSH。"
                        root.add(statusPanel, BorderLayout.CENTER)
                        root.revalidate()
                        return
                    }
                    val created = JBCefBrowser()
                    browser = created
                    installBridges(created)
                    root.add(created.component, BorderLayout.CENTER)
                    pageLoaded = false
                    created.loadURL(target)
                } else if (loadedUrl != target) {
                    pageLoaded = false
                    existing.loadURL(target)
                }
                loadedUrl = target
                root.revalidate()
                root.repaint()
            }
            DshState.STARTING -> {
                statusLabel.text = "正在启动 DSH（端口 ${status.port}）…"
                setRetryVisible(false)
                showStatusPanel()
            }
            DshState.FAILED -> {
                statusLabel.text = "DSH 启动失败：${status.detail ?: "未知原因"}"
                // 仅 FAILED 暴露「重试」入口：改完设置（命令/端口等）点一下即可重新启动。
                // RUNNING 中改设置没有运行中重启入口，需重启 IDE 或服务。
                setRetryVisible(true)
                showStatusPanel()
            }
            DshState.STOPPED -> {
                statusLabel.text = "DSH 未运行。"
                setRetryVisible(false)
                showStatusPanel()
            }
        }
    }

    private fun setRetryVisible(visible: Boolean) {
        if (retryRow.isVisible == visible && retryButton.isVisible == visible) return
        retryRow.isVisible = visible
        retryButton.isVisible = visible
        statusPanel.revalidate()
        statusPanel.repaint()
    }

    private fun showStatusPanel() {
        if (browser != null) {
            // 保持浏览器画面，只在状态卡可见时叠加（骨架阶段简单处理：切换回状态卡）
        }
        if (statusPanel.parent == null) {
            root.add(statusPanel, BorderLayout.CENTER)
            root.revalidate()
            root.repaint()
        }
    }

    /**
     * 在服务 URL 上追加当前项目路径。
     * 参数放在 fragment：带 token 的首次 303 会丢弃 query，但按 RFC 7231 会继承 fragment。
     */
    private fun browserUrl(base: String): String = DshWebUrl.workspaceUrl(base, project.basePath)

    /**
     * 注入两个方向的通道：
     *
     * 1. DSH 页面 → IDE（打开文件）：页面侧（`dsh-plugin-jetbrains/client.js`）点击文件引用时调用
     *    `window.__dshIdeOpenFile(JSON.stringify({ path, line, column }))`，这里用
     *    [JBCefJSQuery] 把调用转成 IDE 动作。
     * 2. IDE → DSH 页面（添加到对话）：[pushToWebview] 调用页面里的
     *    `window.__dshIdePush(json)`；页面未就绪时先入队。
     *
     * 页面每次导航/刷新都会重建 JS 上下文，所以在 `onLoadEnd` 里重新注入。
     */
    private fun installBridges(target: JBCefBrowser) {
        val query = JBCefJSQuery.create(target)
        query.addHandler { payload ->
            handleOpenFileRequest(payload)
            null
        }
        // 「重启宿主 Web」：DSH 插件（设置页）通过这个桥请求 IDE 重启它托管的 dsh 进程。
        // 重启动作只是投递到 lifecycle 线程，不阻塞 JCEF 线程；页面侧拿不到返回值时自行重试轮询。
        val restartQuery = JBCefJSQuery.create(target)
        restartQuery.addHandler {
            log.info("DSH 页面请求重启宿主 Web")
            service.restartAsync()
            null
        }
        // __dshIdePush 设一个「页面侧还没装好就排队」的中转：客户端补丁 apply 时把
        // __dshIdeInsertRefs 注册进来并冲刷队列（见 client.js 的 installComposerBridge）。
        val script = """
            window.__dshIdeOpenFile = function(payload) { ${query.inject("payload")} };
            window.__dshIdeRestartWeb = function(reason) { ${restartQuery.inject("reason")} };
            window.__dshIdeHost = { version: "0.2.1", restartWeb: true };
            (function () {
              window.__dshIdePendingPushes = window.__dshIdePendingPushes || [];
              window.__dshIdePush = function (json) {
                if (typeof window.__dshIdeInsertRefs === 'function') window.__dshIdeInsertRefs(json);
                else window.__dshIdePendingPushes.push(json);
              };
            })();
        """.trimIndent()
        val handler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true && cefBrowser != null) {
                    cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
                    pageLoaded = true
                    drainPendingPushes()
                }
            }
        }
        target.jbCefClient.addLoadHandler(handler, target.cefBrowser)
        openFileQuery = query
        restartWebQuery = restartQuery
        openFileLoadHandler = handler
    }

    /**
     * IDE → 页面：把一条 JSON 消息推给 `window.__dshIdePush`。
     * 页面未加载完成时先排队，由 [installBridges] 的 `onLoadEnd` 冲刷；JCEF 不在手时静默保留。
     */
    fun pushToWebview(json: String) {
        synchronized(pendingPushes) { pendingPushes.addLast(json) }
        drainPendingPushes()
    }

    private fun drainPendingPushes() {
        val target = browser ?: return
        if (!pageLoaded) return
        val messages = synchronized(pendingPushes) {
            if (pendingPushes.isEmpty()) return
            pendingPushes.toList().also { pendingPushes.clear() }
        }
        ApplicationManager.getApplication().invokeLater {
            val cefBrowser = target.cefBrowser
            messages.forEach { message ->
                // Gson 生成带转义的 JSON 字符串字面量，避免手拼引号。
                val literal = gson.toJson(message)
                runCatching {
                    cefBrowser.executeJavaScript(
                        "window.__dshIdePush && window.__dshIdePush($literal)",
                        cefBrowser.url,
                        0,
                    )
                }.onFailure { log.warn("DSH 推送到页面失败: ${it.message}") }
            }
        }
    }

    /** payload 是页面传来的 JSON `{ path, line?, column? }`；解析失败只告警，不影响页面。 */
    private fun handleOpenFileRequest(payload: String) {
        val json = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
        if (json == null) {
            log.warn("DSH 打开文件请求解析失败: $payload")
            return
        }
        val path = json.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (path.isEmpty()) return
        val line = json.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val column = json.get("column")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        log.info("DSH 打开文件: $path:$line:$column")
        ApplicationManager.getApplication().invokeLater { openInEditor(path, line, column) }
    }

    /** 相对路径按当前 IDE 项目根解析（DSH 会话 cwd 就是这个项目）；行/列从 1 开始，IDE 从 0 开始。 */
    private fun openInEditor(rawPath: String, line: Int, column: Int) {
        if (project.isDisposed) return
        val base = project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }
        val resolved = runCatching {
            val path = Paths.get(rawPath)
            if (path.isAbsolute) path else base?.resolve(path) ?: path
        }.getOrNull() ?: return
        if (!Files.isRegularFile(resolved)) {
            log.warn("DSH 请求打开的文件不存在: $resolved")
            return
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolved) ?: return
        val descriptor = if (line > 0) {
            OpenFileDescriptor(project, virtualFile, maxOf(line - 1, 0), maxOf(column - 1, 0))
        } else {
            OpenFileDescriptor(project, virtualFile)
        }
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    override fun dispose() {
        service.removeListener(onStatus)
        ideService.detachPanel(this)
        synchronized(pendingPushes) { pendingPushes.clear() }
        pageLoaded = false
        browser?.let { target ->
            openFileLoadHandler?.let { handler ->
                runCatching { target.jbCefClient.removeLoadHandler(handler, target.cefBrowser) }
            }
            Disposer.dispose(target)
        }
        openFileLoadHandler = null
        openFileQuery?.dispose()
        openFileQuery = null
        restartWebQuery?.dispose()
        restartWebQuery = null
        browser = null
    }
}
