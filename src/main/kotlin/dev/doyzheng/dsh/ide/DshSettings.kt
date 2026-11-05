package dev.doyzheng.dsh.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件全局设置（应用级，全 IDE 共用一份）。
 *
 * 关键取向与 zcode-ide 一致：**全 IDE 只跑一个 dsh 实例、固定端口**。
 * 端口固定才能让所有项目窗口同 origin，localStorage / 会话当前项才不会丢；
 * 项目维度的区分靠 URL 参数 `ideWorkspace`，而不是靠每项目一个进程。
 */
@State(name = "DshIdeSettings", storages = [Storage("dsh-ide.xml")])
class DshSettings : PersistentStateComponent<DshSettings.Settings> {

    data class Settings(
        /**
         * dsh 可执行文件。默认 `dsh`：解析顺序为显式配置 → PATH → 桌面端内置 runtime，
         * 都找不到时报错（服务转 `FAILED`，detail 给出已查找路径）。
         * 与官方桌面端共用同一个安装，才能"复用官方客户端数据"。
         */
        var dshCommand: String = "dsh",
        /** 监听地址，保持回环。 */
        var host: String = "127.0.0.1",
        /**
         * 固定端口。默认 45231，被占用时启动阶段向上探测。
         * 不允许设为 0：动态端口会让每个窗口变成不同 origin，localStorage 全丢。
         */
        var port: Int = DEFAULT_PORT,
        /**
         * 使用的 DSH profile。使用 `idea` 而不是复用 `web`：
         * 桌面端用 `desktop` profile（CLI 拒绝 boot），IDE 用自己的 profile，
         * 但 sessions / storages / .credentials.yaml 仍是同一个 `$DSH_HOME`。
         */
        var profile: String = DEFAULT_PROFILE,
        /** 项目打开时自动展示 DSH 工具窗口；不控制系统服务的启停（服务由面板的打开/重试触发）。 */
        var autoStart: Boolean = true,
        /** 有桌面端在运行时是否仍启动（只提示，不阻塞）。 */
        var warnWhenDesktopRunning: Boolean = true,
    )

    private var settings = Settings()

    val current: Settings get() = settings

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        // 迁移老版本持久化设置：profile 名曾为 `dsh-ide`，现统一为 `idea`。
        if (state.profile == "dsh-ide") state.profile = "idea"
        settings = state
    }

    companion object {
        const val DEFAULT_PORT = 45231
        const val DEFAULT_PROFILE = "idea"

        @JvmStatic
        fun getInstance(): DshSettings =
            ApplicationManager.getApplication().getService(DshSettings::class.java)
    }
}
