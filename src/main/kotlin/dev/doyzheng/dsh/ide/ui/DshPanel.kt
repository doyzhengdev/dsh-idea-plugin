package dev.doyzheng.dsh.ide.ui

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.ex.FileTypeChooser
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import dev.doyzheng.dsh.ide.DshIdeService
import dev.doyzheng.dsh.ide.DshProfileBootstrap
import dev.doyzheng.dsh.ide.DshServerService
import dev.doyzheng.dsh.ide.DshSettings
import dev.doyzheng.dsh.ide.DshState
import dev.doyzheng.dsh.ide.DshStatus
import dev.doyzheng.dsh.ide.DshWebUrl
import java.awt.BorderLayout
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import javax.swing.JPanel
import javax.swing.SwingConstants
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

    private var browser: JBCefBrowser? = null
    private var loadedUrl: String? = null

    /** 相同状态页不重复加载（下载进度每 5% 会刷新一次文案）。 */
    private var lastStatusHtml: String? = null

    /** DSH 页面 → IDE 的「打开文件」通道；页面里的 `window.__dshIdeOpenFile` 由它注入。 */
    private var openFileQuery: JBCefJSQuery? = null
    private var openFileLoadHandler: CefLoadHandlerAdapter? = null

    /** DSH 页面 → IDE 的「重启宿主 Web」通道；页面里的 `window.__dshIdeRestartWeb` 由它注入。 */
    private var restartWebQuery: JBCefJSQuery? = null

    /** DSH 页面 → IDE 的「打开外部链接」通道；系统浏览器不可用时回退内置 JCEF。 */
    private var externalLinkQuery: JBCefJSQuery? = null

    /** 状态页按钮（重试 / 启动）→ Kotlin。 */
    private var statusActionQuery: JBCefJSQuery? = null

    /** JCEF 原生右键菜单本地化。 */
    private var contextMenuHandler: DshJcefContextMenuHandler? = null

    /** IDE → 页面的「添加到对话」通道：页面加载完成前先排队，`onLoadEnd` 后冲刷。 */
    private val pendingPushes = ArrayDeque<String>()
    private var pageLoaded = false
    /** 浏览器当前显示的是状态页（不是 DSH 应用）：状态页的 onLoadEnd 不能冲刷推送队列。 */
    private var showingStatus = true
    private val gson = Gson()
    private val log = thisLogger()

    /** 启动耗时诊断：面板创建 → 首次状态页可见（JCEF 初始化）→ 加载 DSH 页面 → 页面加载完成。 */
    private val createdAtNanos = System.nanoTime()
    private var loadUrlAtNanos = 0L
    private var statusPageLogged = false

    private val onStatus: (DshStatus) -> Unit = { status ->
        SwingUtilities.invokeLater { render(status) }
    }

    val component: JPanel get() = root

    init {
        service.addListener(onStatus)
        ensureJcefLocale()
        if (JBCefApp.isSupported()) {
            val created = JBCefBrowser()
            browser = created
            installBridges(created)
            root.add(created.component, BorderLayout.CENTER)
            log.info("[dsh] 面板创建，开始初始化 JCEF")
        } else {
            root.add(JBLabel("当前 IDE 不支持 JCEF，无法显示 DSH 界面。", SwingConstants.CENTER), BorderLayout.CENTER)
        }
        render(service.currentStatus())
        ideService.attachPanel(this)
    }

    fun onShown() {
        // 只在工具窗口 content 创建时被调用（DshToolWindowFactory.createToolWindowContent）：
        // 宿主已由 DshStartupActivity 在启动完成后后台启动，用户点击 DSH 图标创建面板时通常直接
        // 显示 RUNNING 页面；STOPPED/FAILED（启动失败或手动停止）时这里会重新启动。
        // 不是每次显示都触发（普通显示/隐藏不会重跑）；STOPPED/FAILED 时重建 content 也算一次重试。
        val state = service.currentStatus().state
        if (state == DshState.STOPPED || state == DshState.FAILED) service.startAsync()
        render(service.currentStatus())
    }

    /**
     * JCEF 的右键菜单（Copy / Paste...）由 Chromium 的 locale 决定，不是页面语言。
     * 在 JCEF 尚未启动时把 CefSettings.locale 设成 IDEA 当前语言，
     * 这样 Chromium 原生菜单会跟随 IDE（中文 IDE → “复制/粘贴”）。
     */
    private fun ensureJcefLocale() {
        if (JBCefApp.isStarted()) return
        val languageTag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        runCatching { JBCefApp.getInstance().cefSettings?.locale = languageTag }
            .onFailure { log.warn("设置 JCEF locale 失败: $languageTag", it) }
    }

    private fun render(status: DshStatus) {
        val target = browser ?: return
        when (status.state) {
            DshState.RUNNING -> {
                val url = status.url ?: return
                val next = browserUrl(url)
                if (loadedUrl != next) {
                    loadedUrl = next
                    lastStatusHtml = null
                    showingStatus = false
                    pageLoaded = false
                    loadUrlAtNanos = System.nanoTime()
                    log.info("[dsh] 宿主就绪，加载运行页面（面板创建后 ${(loadUrlAtNanos - createdAtNanos) / 1_000_000} ms）")
                    target.loadURL(next)
                }
            }
            DshState.STARTING -> {
                val detail = status.detail.orEmpty()
                val downloading = detail.contains("下载") || detail.contains("未检测到")
                val installing = !downloading &&
                    (detail.contains("安装") || detail.contains("等待") || detail.contains("复制"))
                val title = when {
                    downloading -> "正在下载 DeepSeek Harness"
                    installing -> "正在安装 DeepSeek Harness"
                    else -> "正在启动 DSH"
                }
                val description = when {
                    downloading -> "$detail\n首次使用需要下载安装官方客户端，可能需要几分钟。"
                    installing -> detail
                    detail.isBlank() || detail.startsWith("已检测到") -> "端口 ${status.port}"
                    else -> detail
                }
                showStatusPage(
                    title = title,
                    detail = description,
                    action = null,
                    loading = true,
                    progress = downloading || installing,
                )
            }
            DshState.NEED_CLIENT -> showStatusPage(
                title = "需要 DeepSeek Harness 客户端",
                detail = "DSH for Desktop 复用官方 DeepSeek Harness 桌面客户端的运行时。\n" +
                    "点击下方按钮后，将从 download.deepseek.com 下载安装包并安装（约 276 MB）。",
                action = StatusAction("install-client", "下载并安装"),
                loading = false,
            )
            DshState.FAILED -> showStatusPage(
                title = "DSH 启动失败",
                detail = status.detail ?: "未知原因",
                action = StatusAction("retry", "重试"),
                loading = false,
            )
            DshState.STOPPED -> showStatusPage(
                title = "DSH 未运行",
                detail = "点击下方按钮启动，或从右上角齿轮打开设置。",
                action = StatusAction("start", "启动 DSH"),
                loading = false,
            )
        }
    }

    private data class StatusAction(val id: String, val label: String)

    /**
     * 用 HTML 渲染状态页：居中、加载动画和按钮样式全部交给 CSS。
     * 浏览器始终留在布局里，只切换页面内容，避免 JCEF 原生画面盖住 Swing 状态页。
     */
    private fun showStatusPage(
        title: String,
        detail: String,
        action: StatusAction?,
        loading: Boolean,
        progress: Boolean = false,
    ) {
        val target = browser ?: return
        val html = statusHtml(title, detail, action, loading, progress)
        if (html == lastStatusHtml) return
        lastStatusHtml = html
        showingStatus = true
        target.loadHTML(html)
    }

    private fun statusHtml(
        title: String,
        detail: String,
        action: StatusAction?,
        loading: Boolean,
        progress: Boolean,
    ): String {
        val spinner = if (loading) """<div class="spinner"></div>""" else ""
        val bar = if (progress) """<div class="bar"></div>""" else ""
        val button = action?.let {
            """<button type="button" onclick="act('${it.id}')">${HtmlPages.escape(it.label)}</button>"""
        } ?: ""
        return HtmlPages.render(
            "status.html",
            mapOf(
                "THEME" to if (JBColor.isBright()) "light" else "dark",
                "SPINNER" to spinner,
                "TITLE" to HtmlPages.escape(title),
                "DETAIL" to HtmlPages.escape(detail),
                "BAR" to bar,
                "BUTTON" to button,
            ),
        )
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
        // 「打开外部链接」：尽量用系统默认浏览器；没有可用系统浏览器时回退内置 JCEF。
        val externalQuery = JBCefJSQuery.create(target)
        externalQuery.addHandler { url ->
            openExternalUrl(url)
            null
        }
        // 状态页按钮（重试 / 启动）→ Kotlin。
        val statusQuery = JBCefJSQuery.create(target)
        statusQuery.addHandler { action ->
            when (action) {
                "retry", "start" -> SwingUtilities.invokeLater { service.startAsync() }
                "install-client" -> SwingUtilities.invokeLater { service.installClientAndStartAsync() }
            }
            null
        }
        // DSH 设置页的「打开配置文件」在 JCEF 里调宿主原生打开容易被系统文件关联拦截；
        // 把 profile 的 cordis.patch.yml 路径注入页面，Client 半边会把它交给 IDE 打开。
        val profile = DshSettings.getInstance().current.profile.ifBlank { DshSettings.DEFAULT_PROFILE }
        val settingsDocumentPath = DshProfileBootstrap.profileDir(profile)
            .resolve("cordis.patch.yml")
            .toAbsolutePath()
            .normalize()
            .toString()
        // __dshIdePush 设一个「页面侧还没装好就排队」的中转：客户端补丁 apply 时把
        // __dshIdeInsertRefs 注册进来并冲刷队列（见 client.js 的 installComposerBridge）。
        val script = """
            window.__dshIdeOpenFile = function(payload) { ${query.inject("payload")} };
            window.__dshIdeRestartWeb = function(reason) { ${restartQuery.inject("reason")} };
            window.__dshIdeOpenExternal = function(url) { ${externalQuery.inject("url")} };
            window.__dshIdeStatusAction = function(action) { ${statusQuery.inject("action")} };
            window.__dshIdeSettingsDocument = ${gson.toJson(settingsDocumentPath)};
            window.__dshIdeHost = { version: "1.0.0", restartWeb: true };
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
                    // 状态页（data: 页面）加载完成不能算 DSH 应用就绪，否则会把排队的推送丢掉。
                    if (!showingStatus) {
                        pageLoaded = true
                        drainPendingPushes()
                        log.info(
                            "[dsh] 运行页面加载完成（loadURL 后 ${(System.nanoTime() - loadUrlAtNanos) / 1_000_000} ms）",
                        )
                    } else if (!statusPageLogged) {
                        statusPageLogged = true
                        log.info(
                            "[dsh] 状态页加载完成（面板创建后 ${(System.nanoTime() - createdAtNanos) / 1_000_000} ms）",
                        )
                    }
                }
            }
        }
        target.jbCefClient.addLoadHandler(handler, target.cefBrowser)
        val menuHandler = DshJcefContextMenuHandler()
        target.jbCefClient.addContextMenuHandler(menuHandler, target.cefBrowser)
        openFileQuery = query
        restartWebQuery = restartQuery
        externalLinkQuery = externalQuery
        statusActionQuery = statusQuery
        openFileLoadHandler = handler
        contextMenuHandler = menuHandler
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

    /**
     * 打开外部链接：优先系统默认浏览器；系统无可用浏览器时回退内置 JCEF 窗口。
     */
    private fun openExternalUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val opened = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (opened) {
            log.info("外部链接已交给系统浏览器: $url")
            return
        }
        log.info("系统浏览器不可用，回退内置 JCEF: $url")
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && JBCefApp.isSupported()) {
                DshExternalBrowserDialog(project, url).show()
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
        // 所有文件先让 IDE 判断类型：已知扩展按 IDE 编辑器打开；未知扩展弹出 IDEA
        // 的「选择文件类型/关联」对话框（FileTypeChooser.getKnownFileTypeOrAssociate）。
        val fileType = runCatching { FileTypeChooser.getKnownFileTypeOrAssociate(virtualFile, project) }.getOrNull()
        if (fileType == null) {
            log.info("用户取消文件类型选择: $resolved")
            return
        }
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
            contextMenuHandler?.let { handler ->
                runCatching { target.jbCefClient.removeContextMenuHandler(handler, target.cefBrowser) }
            }
            Disposer.dispose(target)
        }
        openFileLoadHandler = null
        contextMenuHandler = null
        openFileQuery?.dispose()
        openFileQuery = null
        restartWebQuery?.dispose()
        restartWebQuery = null
        externalLinkQuery?.dispose()
        externalLinkQuery = null
        statusActionQuery?.dispose()
        statusActionQuery = null
        browser = null
    }
}
