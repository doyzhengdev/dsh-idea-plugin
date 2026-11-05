# dsh-plugin-jetbrains —— DSH for JetBrains 的 DSH 侧 IDE 桥插件

这是 `dsh-ide` 项目的 **DSH 插件**半边：一个纯 JS 的 Cordis 插件，安装进 DSH profile 后，
把 DSH Web UI 与 IDE 打通。它**不提供设置页面**——检测 JetBrains、安装/卸载 IDE 插件、
profile 管理等全部在 IDEA 插件（Kotlin）的 `Settings → Tools → DSH` 里。

源码位于 `src/main/resources/dsh-plugin-jetbrains/`，构建时随 resources 打进 IDEA 插件 jar 的
`/dsh-plugin-jetbrains` 资源，由 `DshBundleBootstrap` 在每次 IDE 启动时动态注入到 DSH profile。

| 半边 | 位置 | 职责 |
|---|---|---|
| DSH 插件（Cordis） | `src/main/resources/dsh-plugin-jetbrains/`（包名 `dsh-plugin-jetbrains`） | IDE 桥（`ideWorkspace` / 打开文件 / 插入引用 / 侧栏过滤）；Host 半边只是空入口 |
| IDEA 插件（Kotlin，名 `dsh-idea-plugin`） | `src/main/kotlin/`、`META-INF/` | 托管 DSH Web（JCEF），提供全部管理功能（`Settings → Tools → DSH`） |

## 功能

- **IDE 桥**（`src/client.js`，纯浏览器半边）：
  - `ideWorkspace`：按 URL 选中当前 IDE 项目对应的 workspace，侧栏只显示该项目；
  - 打开文件：覆盖 `sidebarRight.openResource/openResourceIn`，文件资源在 IDE 中打开；
  - 添加到对话：把 IDE 右键的文件/选区以 `@引用` 插入输入框。
- **Host 半边**（`src/index.js`）：空入口（`name` + 空 `apply`）。profile 注入由 IDEA 插件的
  `DshBundleBootstrap` 在每次启动完成，这里不需要任何宿主逻辑；保留它只是给 Cordis 一个合法的
  Loader entry（`dsh.client` 的浏览器 bundle 依附于它）。

## 目录

```
src/main/resources/dsh-plugin-jetbrains/
├─ package.json          # 包清单：. / ./client 指向 src/；dsh.bundle.patch + dsh.client
├─ cordis.patch.yml      # 插入本插件行
├─ icon.svg              # 插件图标
├─ locale/{zh,en}.json   # 插件列表显示名与描述
├─ README.md             # 本文件（不打进 jar）
└─ src/
   ├─ index.js           # Host 半边：空入口（无宿主逻辑）
   └─ client.js          # Client 半边：IDE 桥（ideWorkspace / 打开文件 / 插入引用 / 侧栏过滤）
```

## 与 IDEA 插件的关系

IDEA 插件构建时把本目录运行所需文件（`package.json` / `cordis.patch.yml` / `src/index.js` /
`src/client.js` / `icon.svg` / `locale/**`）打进 jar 的 `/dsh-plugin-jetbrains` 资源；
`DshBundleBootstrap` 在每次 IDE 启动时把它们注入到当前 profile：

- 拷贝到 `<profile>/node_modules/dsh-plugin-jetbrains`；
- 若 profile 的 `dsh.profile.bundles` 已含本包 → 走 bundle 加载；否则写 `dsh-plugin-jetbrains.patch.yml` 并 `--patch` 挂载。

因此引用/复制/新建的 profile 都不会缺 IDE 桥插件。
