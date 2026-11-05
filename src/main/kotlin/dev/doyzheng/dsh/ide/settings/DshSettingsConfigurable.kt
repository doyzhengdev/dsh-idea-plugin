package dev.doyzheng.dsh.ide.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.doyzheng.dsh.ide.DshSettings
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent

/**
 * Settings → Tools → DSH。
 *
 * 两块内容：
 *  1. **启动设置**（本类直接读写 [DshSettings.Settings] 的字段，点 OK/Apply 保存）；
 *  2. **管理面板** [DshManagementPanel]：检测本机 JetBrains 工具、把本插件安装/更新/卸载到其它 IDE、
 *     管理 DSH profile（引用/复制/空 + 备份切回）、重启宿主 DSH 服务。管理动作即时生效，不受 OK/取消 影响。
 *
 * 设置项是应用级（全 IDE 共用一份，不按项目区分）。改 command/端口/profile 后需要重启服务或 IDE 才生效。
 */
class DshSettingsConfigurable : Configurable {

    private val commandField = JBTextField()
    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val profileField = JBTextField()
    private val autoStartCheck = JBCheckBox("打开项目时自动展示 DSH 工具窗口")
    private val warnDesktopCheck = JBCheckBox("桌面端正在运行时仍然启动，并给出提示")

    private var panel: JComponent? = null
    private var management: DshManagementPanel? = null

    override fun getDisplayName(): String = "DSH"

    override fun createComponent(): JComponent {
        panel?.let { return it }
        resetFieldsFromSettings()

        val startup = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("启动设置（全 IDE 共用；改动后需重启 DSH 服务或 IDE 才生效）："))
            .addLabeledComponent("dsh 命令：", commandField)
            .addComponent(JBLabel("留空或 dsh：按 PATH → 桌面端 runtime 自动探测；也可填绝对路径"))
            .addLabeledComponent("监听地址：", hostField)
            .addLabeledComponent("端口：", portField)
            .addLabeledComponent("Profile：", profileField)
            .addComponent(autoStartCheck)
            .addComponent(warnDesktopCheck)
            .panel

        val content = JBPanel<JBPanel<*>>()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.add(startup)

        val mgmt = DshManagementPanel(profileNameProvider = { profileField.text.trim() })
        management = mgmt
        content.add(mgmt.component())

        val scroll = JBScrollPane(content)
        scroll.preferredSize = Dimension(760, 640)
        panel = scroll
        return scroll
    }

    override fun isModified(): Boolean {
        val s = DshSettings.getInstance().current
        return s.dshCommand != commandField.text.trim()
            || s.host != hostField.text.trim()
            || s.port != parsePortOrNull()
            || s.profile != profileField.text.trim()
            || s.autoStart != autoStartCheck.isSelected
            || s.warnWhenDesktopRunning != warnDesktopCheck.isSelected
    }

    override fun apply() {
        val port = parsePortOrNull()
            ?: throw ConfigurationException("端口必须是 1 到 65535 之间的整数")
        if (port !in 1..65535) {
            throw ConfigurationException("端口必须是 1 到 65535 之间的整数")
        }
        val s = DshSettings.getInstance().current
        s.dshCommand = commandField.text.trim()
        s.host = hostField.text.trim()
        s.port = port
        s.profile = profileField.text.trim()
        s.autoStart = autoStartCheck.isSelected
        s.warnWhenDesktopRunning = warnDesktopCheck.isSelected
    }

    override fun reset() = resetFieldsFromSettings()

    override fun disposeUIResources() {
        panel = null
        management = null
    }

    private fun resetFieldsFromSettings() {
        val s = DshSettings.getInstance().current
        commandField.text = s.dshCommand
        hostField.text = s.host
        portField.text = s.port.takeIf { it in 1..65535 }?.toString() ?: ""
        profileField.text = s.profile
        autoStartCheck.isSelected = s.autoStart
        warnDesktopCheck.isSelected = s.warnWhenDesktopRunning
    }

    private fun parsePortOrNull(): Int? = portField.text.trim().toIntOrNull()
}
