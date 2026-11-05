<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# DSH for Desktop Changelog

## [1.0.0] - 2026-11-05

### Added

- IDEA 工具窗口内嵌 DeepSeek Harness Web UI（JCEF）；全 IDE 单进程、固定端口，项目用 `ideWorkspace` 区分。
- 复用官方 DeepSeek Harness 桌面客户端运行时；未安装时在状态页由用户确认后下载安装。
- profile `idea` 链接到桌面端 `desktop`，共享 `~/.dsh`（凭据 / 会话 / 设置）。
- IDE 桥：点文件在 IDE 打开、右键文件/选区插入 `@引用`、侧栏只显示当前项目。
- DSH 侧插件 `dsh-plugin-jetbrains`：localStorage 跨端口快照；首次启动经官方 `configEditor` 对齐 IDE 语言/主题。
- 设置弹窗（工具窗口右上角齿轮）：运行信息、客户端/Node 版本、一键修复、重启 DSH 服务。

### Changed

- 插件展示名改为 **DSH for Desktop**（JetBrains Marketplace 不允许插件名包含 “JetBrains”）。
- 未安装官方客户端时不再后台静默下载安装：新增「需要客户端」状态页，用户点击后才下载。

### Security

- 插件签名（`signPlugin`），发布包为 `build/distributions/dsh-idea-plugin-1.0.0-signed.zip`。
