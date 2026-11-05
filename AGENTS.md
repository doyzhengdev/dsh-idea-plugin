# dsh-ide 项目规则

## 交互原则（用户明确要求，优先级最高）

**开发插件期间，凡是当前环境能直接执行的，一律自己做完，不要让用户手动操作。**

包括但不限于：

- 构建、测试、打包（`./gradlew test` / `buildPlugin`）
- 安装 / 替换 IDEA 插件，关闭 / 重启 IDEA，结束残留的 DSH 进程
- 修改 IDEA 配置（`options/dsh-ide.xml`）、DSH profile、junction、端口等
- 查日志、抓进程 / 端口、核对启动命令、跑冒烟验证
- 改代码、改文档、更新记忆

只有以下情况才请用户介入：

- 需要人工视觉 / 体验判断（图标观感、主题效果、界面是否顺眼）
- 需要用户的账号、凭据或外部授权
- 需要用户拍板的高风险取舍（删用户数据、强杀 IDE 且可能丢未保存内容）

执行约定：

- 高风险 / 不可逆操作**先备份再执行**，结果里给出备份路径与回滚方式。
- 不要用“要我关吗 / 要我装吗”来确认可以直接做的事；直接执行并报告结果。
- 关闭或重启 IDE 前，假定 IDE 会自动保存；必要时先 `stop-ide.ps1` 优雅退出，超时才强杀。

## 项目入口

- 进度、历史与待办：`HANDOFF.md`
- 架构依据（源码行号级）：`.agents/docs/architecture.md`
- DSH 插件（Cordis 插件，包名 `dsh-plugin-jetbrains`）：`src/main/resources/dsh-plugin-jetbrains/`
  - Host 半边 `src/index.js`：空入口（无宿主逻辑；profile 注入由 Kotlin `DshBundleBootstrap` 完成）
  - Client 半边 `src/client.js`：纯 IDE 桥（ideWorkspace / 打开文件 / 插入引用 / 侧栏过滤）
- 管理功能（检测 JetBrains / 安装卸载本插件 / profile 管理 / 重启服务）：IDEA 插件 `Settings → Tools → DSH`
  - Kotlin：`settings/`（设置页 UI）、`jetbrains/`（检测 / 安装 / profile 后端）
- IDEA 插件（Kotlin，名 `dsh-idea-plugin`）：**仓库根即 Gradle 工程根**（`build.gradle.kts` / `src/` / `gradle/`）
  - 构建：`./gradlew buildPlugin --offline`
  - 产物：`build/distributions/dsh-idea-plugin-<version>.zip`
- 部署 IDEA 插件（`dsh-idea-plugin`）：`.agents/skills/deploy-dsh-ide-plugin/`（打包 → 关 IDE → 替换 → 重启 → 验证）
- 分析官方源码：`.agents/skills/codegraph-dsh-source/`（先查 codegraph 索引，别先 grep）

## 本机环境（细节见 skills）

- 构建 JDK：`D:\apps\jdk\jdk-21.0.2`（默认 `JAVA_HOME` 是 JDK 8，必须显式指定）
- IDE：`C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2`
- IDEA 插件目录：`%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\dsh-idea-plugin`
- 本机 DSH profile 在 `~/.dsh/profiles/`：引用 desktop 时是 junction（与桌面端共享配置，二者不要同时运行）
