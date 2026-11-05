# dsh-idea-plugin

IntelliJ Platform 插件：在 JetBrains IDE 的侧边栏中托管 DSH（DeepSeek Harness）Web UI。

- 插件 id：`dev.doyzheng.dsh-ide`（保持不变，避免升级被当成新插件）
- 插件名/产物名：`dsh-idea-plugin`
- 宿主：应用级单例，一个 dsh 进程服务所有项目；项目通过 URL fragment `ideWorkspace` 区分
- profile：默认 `idea`，与官方桌面端共享 `~/.dsh`（sessions / storages / 凭据）
- IDE 桥（ideWorkspace / 点文件在 IDE 打开 / 右键插入 `@引用` / 侧栏只显示当前项目）由 DSH 插件
  `dsh-plugin-jetbrains` 的 Client 半边提供；本插件把该插件打包进 jar 资源（`/dsh-plugin-jetbrains`），
  每次启动由 `DshBundleBootstrap` 注入当前 profile（拷贝到 node_modules；profile 未登记 bundle
  时用 `--patch` 挂载），因此引用/复制/新建的 profile 都能用。
- **管理功能都在本插件**：`Settings → Tools → DSH` 提供 JetBrains 工具检测、安装/更新/卸载本插件到其它 IDE、
  profile 管理（引用/复制/空 + 备份切回）、重启 DSH 服务。

## 目录结构

```
.
├── .run/                   Predefined Run/Debug Configurations（runIde / check / verifyPlugin）
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   └── libs.versions.toml  Version catalog（junit）
├── src
│   ├── main
│   │   ├── kotlin/         Kotlin 生产代码（dev.doyzheng.dsh.ide.*）
│   │   └── resources
│   │       ├── META-INF/   plugin.xml / pluginIcon.svg / dsh-ide-jcef.xml
│   │       └── dsh-plugin-jetbrains/  DSH 侧 IDE 桥插件（package.json + src/，打进 jar 的 /dsh-plugin-jetbrains）
│   └── test/kotlin/        单元测试
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── CHANGELOG.md
├── README.md
└── settings.gradle.kts
```

## 构建

```bash
export JAVA_HOME="D:\\apps\\jdk\\jdk-21.0.2"

./gradlew test --no-daemon              # 31 条单测
./gradlew buildPlugin --no-daemon       # → build/distributions/dsh-idea-plugin-<version>.zip
```

> 本机若无外网，IntelliJ Platform Gradle Plugin 会尝试访问 `jb.gg` 而失败，构建请加 `--offline`
> （已缓存 `IC 2025.1` 到 `.intellijPlatform/localPlatformArtifacts`）。

## 安装到本机 IDE

见仓库根 `.agents/skills/deploy-dsh-ide-plugin/`：打包 → 关 IDE → 替换 `plugins\dsh-idea-plugin` → 重启 → 验证。
或在本 IDE 的 `Settings → Tools → DSH` 里把本插件安装/更新到其它 JetBrains IDE（关闭→备份→复制→可选启动）。

## 与官方 awesome-plugin 模板的差异

结构基本一致（`build.gradle.kts` + `settings.gradle.kts` + `gradle/wrapper` + `src/main/{kotlin,resources/META-INF}` +
`gradle.properties` + `.run/` + `gradle/libs.versions.toml`）。有意保留的差异：

- 版本内联固定（Kotlin 2.1.0 / IPGP 2.2.1 / IC 2025.1）以支持离线构建；示例模板用较新的
  `org.jetbrains.intellij.platform.settings` + `intellijIdea("2025.3.x")`。
- 未引入 `org.jetbrains.changelog` 插件（`CHANGELOG.md` 仍保留，便于人工维护）。
- 平台测试用普通 JUnit（未启用 `TestFrameworkType.Platform`），避免离线解析测试框架依赖。
