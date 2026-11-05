<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# dsh-idea-plugin Changelog

## [Unreleased]

- IDEA 工具窗口内嵌 DeepSeek Harness Web UI（JCEF）；全 IDE 单进程、固定端口，项目用 `ideWorkspace` 区分。
- 复用官方 DeepSeek Harness 桌面客户端运行时；未安装时自动下载安装。
- profile `idea` 链接到桌面端 `desktop`，共享 `~/.dsh`（凭据 / 会话 / 设置）。
- IDE 桥：点文件在 IDE 打开、右键文件/选区插入 `@引用`、侧栏只显示当前项目。
- DSH 侧插件 `dsh-plugin-jetbrains`：localStorage 跨端口快照；首次启动经官方 `configEditor` 对齐 IDE 语言/主题。
- 设置弹窗（工具窗口右上角齿轮）：运行信息、客户端/Node 版本、一键修复、重启 DSH 服务。
