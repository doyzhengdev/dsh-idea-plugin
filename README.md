# dsh-idea-plugin

IntelliJ Platform 插件：在 JetBrains IDE 的工具窗口中托管 DeepSeek Harness Web UI。

- 插件 id：`dev.doyzheng.dsh-ide`；插件名 / 产物名：`dsh-idea-plugin`
- 宿主：应用级单例，一个 dsh 进程服务所有项目；项目通过 URL fragment `ideWorkspace` 区分
- profile：默认 `idea`，链接到官方桌面端的 `desktop`，共享 `~/.dsh`（sessions / storages / 凭据）
- 运行时：复用官方 DeepSeek Harness 桌面客户端（未安装时自动下载安装）
- IDE 桥由 DSH 插件 `dsh-plugin-jetbrains` 提供；本插件把它打进 jar 并注入当前 profile

## 功能

- DSH 工具窗口（JCEF）；右上角齿轮打开设置弹窗：运行信息、客户端/Node 版本、一键修复、重启 DSH 服务。
- IDE 桥：点文件在 IDE 打开、右键把文件/选区以 `@引用` 插入输入框、侧栏只显示当前项目。
- 首次启动语言/主题跟随 IDE；写入由 DSH 官方 `configEditor` 完成，不破坏 profile 里的注释与其它配置。

## 构建

```bash
export JAVA_HOME="D:\\apps\\jdk\\jdk-21.0.2"   # 默认 JAVA_HOME 是 JDK 8

./gradlew buildPlugin --offline -x test --console=plain   # → build/distributions/dsh-idea-plugin-<version>.zip
./gradlew compileTestKotlin --offline                     # 只编译测试，不跑
./gradlew test --offline                                  # 需要时再跑
```

无外网时必须加 `--offline`，否则 IntelliJ Platform Gradle Plugin 会访问 `jb.gg` 而失败。

## 部署

见 `.agents/skills/deploy-dsh-ide-plugin/`：打包 → 关闭 IDE → 备份 `plugins/dsh-idea-plugin` →
解压 zip → 重启 IDE → 查看 `idea.log` 的 `[dsh]` 行。

## 目录结构

```
src/main/kotlin/                          Kotlin 生产代码
src/main/resources/META-INF/              plugin.xml / 图标 / JCEF 模块声明
src/main/resources/dsh-plugin-jetbrains/  DSH 侧 IDE 桥插件（打进 jar 的 /dsh-plugin-jetbrains）
src/test/kotlin/                          单元测试
.agents/skills/deploy-dsh-ide-plugin/     部署脚本
.agents/docs/architecture.md              架构说明
HANDOFF.md                                交接文档
```
