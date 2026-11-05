package dev.doyzheng.dsh.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.messages.Topic

/**
 * 区分「IDE 启动阶段恢复布局」与「启动完成后的正常流程」。
 *
 * 工具窗口 content 在显示时创建，IDE 启动恢复布局时也可能创建；此时不能由面板触发启动，
 * 避免和 IDE 启动争抢资源。[dev.doyzheng.dsh.ide.DshStartupActivity] 在项目启动完成后置位，
 * 并在后台启动宿主（不展示窗口）。
 *
 * 启动阶段用户已经点开窗口的情况：面板订阅放行事件，[settle] 时若窗口仍可见就补一次启动，
 * 避免用户点了图标却一直停在「未运行」。
 */
internal object DshStartupGate {

    private val SETTLED = Key.create<Boolean>("dsh.ide.startup.settled")

    fun interface Listener {
        fun settled()
    }

    private val TOPIC: Topic<Listener> = Topic.create("DSH startup settled", Listener::class.java)

    fun isSettled(project: Project): Boolean = project.getUserData(SETTLED) == true

    fun subscribe(project: Project, disposable: Disposable, listener: Listener) {
        project.messageBus.connect(disposable).subscribe(TOPIC, listener)
    }

    fun settle(project: Project) {
        if (isSettled(project)) return
        project.putUserData(SETTLED, true)
        project.messageBus.syncPublisher(TOPIC).settled()
    }
}
