package dev.doyzheng.dsh.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件全局设置（应用级，全 IDE 共用一份）。
 *
 * 关键取向：**全 IDE 只跑一个 dsh 实例、固定端口**。端口固定才能让所有项目窗口同 origin，
 * localStorage / 会话当前项才不会丢；项目维度的区分靠 URL 参数 `ideWorkspace`，
 * 而不是靠每项目一个进程。
 */
@State(name = "DshIdeSettings", storages = [Storage("dsh-ide.xml")])
class DshSettings : PersistentStateComponent<DshSettings.Settings> {

    data class Settings(
        /**
         * dsh 可执行命令。
         *
         * 默认 `dsh` 表示**自动模式**：使用官方 DeepSeek Harness 桌面客户端自带的运行时
         * （客户端未安装时自动下载安装包并安装）。
         *
         * 填其他值表示**外部覆盖**：按「显式路径 → PATH」使用用户自己的 dsh，不检测/安装客户端。
         */
        var dshCommand: String = "dsh",
        /** 监听地址，保持回环。 */
        var host: String = "127.0.0.1",
        /**
         * 固定端口。默认 46231，被占用时启动阶段向上探测。
         * 不允许设为 0：动态端口会让每个窗口变成不同 origin，localStorage 全丢。
         */
        var port: Int = DEFAULT_PORT,
        /**
         * 使用的 DSH profile。`idea` 是指向桌面端 `desktop` 的目录链接，
         * 与桌面端共用插件配置与会话；两者仍是同一个 `$DSH_HOME`。
         */
        var profile: String = DEFAULT_PROFILE,
    )

    private var settings = Settings()

    val current: Settings get() = settings

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }

    companion object {
        const val DEFAULT_PORT = 46231
        const val DEFAULT_PROFILE = "idea"

        @JvmStatic
        fun getInstance(): DshSettings =
            ApplicationManager.getApplication().getService(DshSettings::class.java)
    }
}
