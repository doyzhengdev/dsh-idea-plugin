# dsh-plugin-jetbrains —— DSH for Desktop 的 DSH 侧插件

`dsh-ide` 项目的 DSH 插件半边：一个纯 JS 的 Cordis 插件，把 DSH Web UI 与 IDE 打通。
检测/安装客户端、profile 管理、启动宿主等全部在 IDEA 插件（Kotlin）里，本插件不提供设置页面。

| 半边 | 位置 | 职责 |
|---|---|---|
| Host | `src/index.js` | localStorage 跨端口快照路由；首次启动经官方 `configEditor` 写入语言/主题 |
| Client | `src/client.js` | `ideWorkspace` 选中项目、文件在 IDE 打开、右键 @引用、侧栏只显示当前项目 |

## 注入方式

IDEA 插件把本目录打进 jar 的 `/dsh-plugin-jetbrains` 资源，每次启动由 `DshBundleBootstrap` 注入 profile：

- 插件实体写到 `<profile>/.dsh-ide/dsh-plugin-jetbrains`（在 node_modules 之外）；
- `<profile>/node_modules/dsh-plugin-jetbrains` 建目录链接指向实体；
- profile 的 `devDependencies` 写入 `"dsh-plugin-jetbrains": "link:./.dsh-ide/dsh-plugin-jetbrains"`；
- 写 `<profile>/dsh-plugin-jetbrains.patch.yml`，启动命令用 `--patch` 挂载。

devDependencies + `--patch` 让 pnpm 保留插件，同时官方插件管理页/插件市场都不会显示或开关它。

## 目录

```
package.json          # 包清单：. / ./client 指向 src/；dsh.bundle.patch + dsh.client
cordis.patch.yml      # 插入本插件行
icon.svg              # 插件图标
locale/{zh,en}.json   # 插件列表显示名与描述
README.md             # 本文件
src/index.js          # Host 半边
src/client.js         # Client 半边
```
