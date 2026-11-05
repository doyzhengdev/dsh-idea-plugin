<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# dsh-idea-plugin Changelog

## [Unreleased]
- 以 IDEA 插件为主发布：全部管理功能迁到 IDE `Settings → Tools → DSH`（Kotlin）。
  - 检测本机 JetBrains 工具、安装/更新/卸载本插件到其它 IDE（安装源=当前插件目录，关→备份→复制→可选启动）。
  - Profile 管理：复用（junction）/ 选择性复制 / 空 profile + 备份切回；应用后强制 profile 装 `dsh-plugin-jetbrains`。
  - 在设置页重启宿主 DSH 服务。
- DSH 插件 `dsh-plugin-jetbrains` 精简：仅保留 IDE 桥（`client.js`）+ 运行期 `ensureIdeBundle`（`index.js`）；
  删除 `settings.section` 设置页与 `/dsh-ide/*` 管理路由。

## [0.1.9]
- 项目结构调整：Kotlin IDEA 插件迁移到 `dsh-idea-plugin/` 独立 Gradle 工程。
- 插件名改为 `dsh-idea-plugin`（与目录一致）；产物 `dsh-idea-plugin-0.1.9.zip`。
- 移除向 profile 注入 `dsh-ide-client` 的客户端补丁；浏览器补丁并入 DSH 插件 `dsh-plugin-jetbrains`。
- 新增「重启宿主 Web」JCEF 桥 `window.__dshIdeRestartWeb`。
