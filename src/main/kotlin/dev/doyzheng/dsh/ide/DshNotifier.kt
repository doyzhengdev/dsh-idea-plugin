package dev.doyzheng.dsh.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用级通知：桌面端与 IDE 同时运行时的并发提示（P4）。
 *
 * 两边共享同一个 `$DSH_HOME`：会话与凭据有锁保护，但 `storages/` 里的 workspace 列表
 * 是整文件重写（last-write-wins），同时使用可能出现「项目列表少一条 / 顺序变」，
 * 所以提示用户，但不阻塞启动。
 *
 * 不持有 Project 引用：服务是应用级单例，提示也对整个 IDE 只弹一次。
 */
object DshNotifier {

    private const val GROUP_ID = "DSH"
    private const val TITLE = "DSH for JetBrains"
    private const val DESKTOP_RUNNING_CONTENT =
        "桌面端正在运行，同时使用可能让工作区列表相互覆盖；会话与凭据不受影响。"

    /** 进程级去重：`compareAndSet` 保证多个项目几乎同时启动时也只弹一次。 */
    private val desktopRunningNotified = AtomicBoolean(false)

    /**
     * 桌面端正在运行时提示一次。
     *
     * 展示切到 EDT；通知 API 的任何异常都只记日志，不影响 dsh 启动。
     * 展示失败时复位标记，后续启动仍有机会补提示；成功展示过则不再重复。
     */
    fun notifyDesktopRunningOnce() {
        if (!desktopRunningNotified.compareAndSet(false, true)) return
        runCatching {
            ApplicationManager.getApplication().invokeLater { showDesktopRunning() }
        }.onFailure {
            desktopRunningNotified.set(false)
            thisLogger().warn("DSH 通知调度失败", it)
        }
    }

    /** 只在 EDT 调用。 */
    private fun showDesktopRunning() {
        runCatching {
            val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            group.createNotification(TITLE, DESKTOP_RUNNING_CONTENT, NotificationType.WARNING).notify(null)
        }.onFailure {
            desktopRunningNotified.set(false)
            thisLogger().warn("DSH 通知展示失败", it)
        }
    }
}
