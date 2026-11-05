package dev.doyzheng.dsh.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.doyzheng.dsh.ide.DshServerService
import dev.doyzheng.dsh.ide.DshSettings
import dev.doyzheng.dsh.ide.jetbrains.BackupInfo
import dev.doyzheng.dsh.ide.jetbrains.IdePluginInstaller
import dev.doyzheng.dsh.ide.jetbrains.InstallResult
import dev.doyzheng.dsh.ide.jetbrains.JetBrainsDetector
import dev.doyzheng.dsh.ide.jetbrains.JetBrainsIde
import dev.doyzheng.dsh.ide.jetbrains.ProfileInfo
import dev.doyzheng.dsh.ide.jetbrains.ProfileManager
import dev.doyzheng.dsh.ide.jetbrains.ProfileResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * IDE 插件管理 + profile 管理面板（Settings → Tools → DSH 的下半部分）。
 *
 * 所有耗时操作（扫描 IDE、安装/卸载、profile 复制）都放到后台线程执行，
 * 完成后回到 EDT 刷新界面；忙碌时禁用全部按钮。破坏性操作先二次确认。
 */
class DshManagementPanel(private val profileNameProvider: () -> String) {

    private val root = JBPanel<JBPanel<*>>()
    private val idesBox = JBPanel<JBPanel<*>>()
    private val profilesLabel = JBLabel("")
    private val statusLabel = JBLabel("就绪")
    private val logArea = JBTextArea(6, 80).apply {
        isEditable = false
        lineWrap = true
        border = JBUI.Borders.empty(6)
    }

    private val installAfterCheck = JCheckBox("安装后启动该 IDE", false)
    private val projectField = JBTextField()

    private val modeCombo = JComboBox(arrayOf("复用（目录链接）", "复制（可选内容）", "创建空 profile"))
    private val sourceCombo = JComboBox<String>()
    private val itemChecks = LinkedHashMap<String, JCheckBox>()
    private val restoreBox = JBPanel<JBPanel<*>>()

    private var busy = false
    private var ides: List<JetBrainsIde> = emptyList()
    private var profiles: List<ProfileInfo> = emptyList()
    private var backups: List<BackupInfo> = emptyList()

    private val targetProfile: String
        get() = profileNameProvider().ifBlank { DshSettings.DEFAULT_PROFILE }

    fun component(): JComponent {
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(0, 4)

        root.add(titled("JetBrains 工具（把本插件安装 / 更新到其它 IDE）"))
        root.add(installOptionsRow())
        idesBox.layout = BoxLayout(idesBox, BoxLayout.Y_AXIS)
        idesBox.alignmentX = 0f
        root.add(idesBox)
        root.add(titled("Profile 管理（当前 IDE 使用的 profile：$targetProfile）"))
        root.add(profileControls())
        root.add(titled("备份 / 切回"))
        restoreBox.layout = BoxLayout(restoreBox, BoxLayout.Y_AXIS)
        restoreBox.alignmentX = 0f
        root.add(restoreBox)
        root.add(titled("现有 profiles"))
        root.add(profilesLabel)
        root.add(titled("宿主 DSH 服务"))
        root.add(restartRow())
        root.add(statusLabel)
        root.add(JBScrollPane(logArea).apply { preferredSize = Dimension(600, 120) })

        refresh()
        return root
    }

    // -----------------------------------------------------------------------------------------
    // 布局片段
    // -----------------------------------------------------------------------------------------

    private fun titled(text: String): JComponent = TitledSeparator(text).apply {
        alignmentX = 0f
        border = JBUI.Borders.emptyTop(10)
    }

    private fun installOptionsRow(): JComponent = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        alignmentX = 0f
        add(installAfterCheck)
        add(JBLabel("启动时打开的项目（可选）:"))
        projectField.preferredSize = Dimension(320, projectField.preferredSize.height)
        add(projectField)
        add(actionButton("刷新", false) { refresh() })
    }

    private fun profileControls(): JComponent {
        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = 0f

        val modeRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = 0f
            add(JBLabel("方式:"))
            modeCombo.preferredSize = Dimension(180, modeCombo.preferredSize.height)
            add(modeCombo)
            add(JBLabel("来源:"))
            sourceCombo.preferredSize = Dimension(140, sourceCombo.preferredSize.height)
            add(sourceCombo)
            add(actionButton("应用（会先备份）", true) { applyProfile() })
        }
        panel.add(modeRow)

        val itemsRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            alignmentX = 0f
            add(JBLabel("复制内容:"))
            for (item in ProfileManager.COPYABLE_ITEMS) {
                val check = JCheckBox(item, item in setOf("package.json", "cordis.patch.yml", "pnpm-workspace.yaml"))
                itemChecks[item] = check
                add(check)
            }
        }
        panel.add(itemsRow)
        return panel
    }

    private fun restartRow(): JComponent = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        alignmentX = 0f
        add(actionButton("重启 DSH 服务", true) { restartService() })
        add(JBLabel("修改 command / 端口 / profile 后，点这里或重启 IDE 生效。"))
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit): JButton {
        val btn = JButton(text)
        if (primary) btn.font = btn.font.deriveFont(Font.BOLD)
        btn.addActionListener { if (!busy) onClick() }
        btn.isEnabled = !busy
        return btn
    }

    // -----------------------------------------------------------------------------------------
    // 刷新
    // -----------------------------------------------------------------------------------------

    fun refresh() {
        if (busy) return
        setBusy(true, "正在检测本机 JetBrains 工具…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val detected = runCatching { JetBrainsDetector.detect() }.getOrDefault(emptyList())
            val profileList = runCatching { ProfileManager.listProfiles() }.getOrDefault(emptyList())
            val backupList = runCatching { ProfileManager.listBackups(targetProfile) }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                ides = detected
                profiles = profileList
                backups = backupList
                rebuildIdes()
                rebuildProfiles()
                rebuildBackups()
                setBusy(false, "检测到 ${detected.size} 个 IDE，${profileList.size} 个 profile")
            }
        }
    }

    private fun rebuildIdes() {
        idesBox.removeAll()
        if (ides.isEmpty()) {
            idesBox.add(JBLabel("未检测到本机安装的 JetBrains IDE。"))
        } else {
            for (ide in ides) idesBox.add(ideCard(ide))
        }
        idesBox.revalidate()
        idesBox.repaint()
    }

    private fun ideCard(ide: JetBrainsIde): JComponent {
        val card = JBPanel<JBPanel<*>>()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.alignmentX = 0f
        card.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8),
        )
        val title = buildString {
            append(ide.name)
            ide.version?.let { append("  v$it") }
            ide.buildNumber?.let { append("  · $it") }
            append(if (ide.running) "  [运行中]" else "  [未运行]")
            append(if (ide.installed.installed) "  [已装插件 v${ide.installed.version ?: "?"}]" else "  [未装插件]")
        }
        card.add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) })
        card.add(JBLabel("安装目录: ${ide.installPath}"))
        card.add(JBLabel("插件目录: ${ide.pluginsDir}"))

        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = 0f
            add(actionButton(if (ide.installed.installed) "安装 / 更新" else "安装", true) { install(ide) })
            add(actionButton("卸载", false) { uninstall(ide) })
            if (ide.running) add(actionButton("关闭", false) { stopIde(ide) })
            else add(actionButton("启动", false) { startIde(ide) })
        }
        card.add(row)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    private fun rebuildProfiles() {
        profilesLabel.text = if (profiles.isEmpty()) "（无）" else "<html>" + profiles.joinToString("<br>") { p ->
            val link = if (p.kind == "link" && p.target != null) " → ${p.target}" else ""
            val plugin = if (p.hasIdePlugin) "插件 ✓" else "插件 ✗"
            "${p.name} · ${p.kind}$link · $plugin" + if (p.name == targetProfile) "  (当前)" else ""
        } + "</html>"

        val previous = sourceCombo.selectedItem as? String
        sourceCombo.removeAllItems()
        for (p in profiles) if (p.name != targetProfile) sourceCombo.addItem(p.name)
        if (previous != null && items(sourceCombo).contains(previous)) {
            sourceCombo.selectedItem = previous
        } else if (sourceCombo.itemCount > 0) {
            sourceCombo.selectedItem = if (items(sourceCombo).contains("desktop")) "desktop" else sourceCombo.getItemAt(0)
        }
    }

    private fun items(combo: JComboBox<String>): List<String> = (0 until combo.itemCount).map { combo.getItemAt(it) }

    private fun rebuildBackups() {
        restoreBox.removeAll()
        if (backups.isEmpty()) {
            restoreBox.add(JBLabel("暂无备份。"))
        } else {
            for (backup in backups) {
                val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                    alignmentX = 0f
                    add(JBLabel(backup.name))
                    add(actionButton("切回", false) { restore(backup) })
                }
                restoreBox.add(row)
            }
        }
        restoreBox.revalidate()
        restoreBox.repaint()
    }

    // -----------------------------------------------------------------------------------------
    // 动作
    // -----------------------------------------------------------------------------------------

    private fun install(ide: JetBrainsIde) {
        if (Messages.showYesNoDialog(
                "安装将先强制关闭目标 IDE（可能丢失未保存内容），备份后覆盖插件。\n目标：${ide.name}（${ide.installPath}）。是否继续？",
                "安装 IDEA 插件", Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        val startAfter = installAfterCheck.isSelected
        val project = projectField.text.trim()
        runTask("正在安装到 ${ide.name}…") {
            IdePluginInstaller.install(ide, stopRunning = true, startAfter = startAfter, force = true, projectPath = project)
        }
    }

    private fun uninstall(ide: JetBrainsIde) {
        if (Messages.showYesNoDialog(
                "卸载将关闭目标 IDE 并删除插件目录（会先备份）。\n目标：${ide.name}。是否继续？",
                "卸载 IDEA 插件", Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        runTask("正在从 ${ide.name} 卸载…") {
            IdePluginInstaller.uninstall(ide, stopRunning = true, force = true)
        }
    }

    private fun startIde(ide: JetBrainsIde) {
        val project = projectField.text.trim()
        runTask("正在启动 ${ide.name}…") { IdePluginInstaller.startIde(ide, project) }
    }

    private fun stopIde(ide: JetBrainsIde) {
        if (Messages.showYesNoDialog("关闭将强制结束 ${ide.name} 的进程（可能丢失未保存内容）。是否继续？", "关闭 IDE", Messages.getWarningIcon()) != Messages.YES) return
        runTask("正在关闭 ${ide.name}…") { IdePluginInstaller.stopIde(ide, force = true) }
    }

    private fun applyProfile() {
        val mode = when (modeCombo.selectedIndex) {
            0 -> "reuse"
            1 -> "copy"
            else -> "empty"
        }
        val source = sourceCombo.selectedItem as? String
        if (mode != "empty" && source.isNullOrBlank()) {
            Messages.showWarningDialog("请先选择来源 profile。", "Profile 管理")
            return
        }
        val selectedItems = itemChecks.filter { it.value.isSelected }.keys.toList()
        if (Messages.showYesNoDialog(
                "将替换 profile「$targetProfile」（会先自动备份）。是否继续？",
                "应用 Profile", Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        val target = targetProfile
        runTask("正在应用 profile（$mode）…") { ProfileManager.apply(target, mode, source, selectedItems) }
    }

    private fun restore(backup: BackupInfo) {
        if (Messages.showYesNoDialog("将删除当前 profile 并从备份「${backup.name}」恢复。是否继续？", "切回 Profile", Messages.getWarningIcon()) != Messages.YES) return
        val target = targetProfile
        runTask("正在从备份切回…") { ProfileManager.restore(target, backup.path) }
    }

    private fun restartService() {
        DshServerService.getInstance().restartAsync()
        statusLabel.text = "已请求重启 DSH 服务（后台进行）。"
    }

    // -----------------------------------------------------------------------------------------
    // 后台任务
    // -----------------------------------------------------------------------------------------

    private fun runTask(label: String, block: () -> Any) {
        setBusy(true, label)
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching { block() }.getOrElse { e ->
                Outcome(false, emptyList(), e.message ?: e.toString())
            }.let { result ->
                when (result) {
                    is InstallResult -> Outcome(result.ok, result.steps, result.error)
                    is ProfileResult -> Outcome(result.ok, result.steps, result.error)
                    else -> Outcome(true, listOf(result.toString()), null)
                }
            }
            SwingUtilities.invokeLater {
                logArea.text = buildString {
                    outcome.steps.forEach { append(it).append('\n') }
                    outcome.error?.let { append(it) }
                }.trim()
                setBusy(false, if (outcome.ok) "完成" else "失败：${outcome.error ?: "未知错误"}")
                refresh()
            }
        }
    }

    private data class Outcome(val ok: Boolean, val steps: List<String>, val error: String?)

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        statusLabel.text = message
        setButtonsEnabled(root, !value)
    }

    private fun setButtonsEnabled(component: Component, enabled: Boolean) {
        if (component is JButton) component.isEnabled = enabled
        if (component is Container) {
            for (child in component.components) setButtonsEnabled(child, enabled)
        }
    }
}
