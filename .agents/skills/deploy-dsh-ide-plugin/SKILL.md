---
name: deploy-dsh-ide-plugin
description: 把 DSH for JetBrains 插件的构建产物部署到本机 IntelliJ IDEA，流程是“打包 → 关闭 IDE → 替换插件目录 → 重启 → 验证”。当用户说“打包插件”“部署到 IDEA”“替换插件”“装到 IDEA 里看看”“改完 Kotlin 需要看效果”，或者要在真实 IDE 里验证 DSH 工具窗口时，使用本 skill。只改代码、不要求在 IDE 里看效果时不要用。
---

# 部署 DSH for JetBrains 插件到本机 IDEA

本 skill 替代 Settings → Plugins → Install Plugin from Disk：直接替换已安装插件目录，重启 IDE 生效。
IntelliJ 的自定义插件没有额外注册表，`plugins/` 下就是一个目录，启动时扫描；
`system/plugins/*.zip` 只是安装缓存，启动不读它。

> 来源：本 skill 与脚本复制自 `E:\gitee\doyzheng\zcode-ide\.agents\skills\deploy-zcode-plugin`，
> 已按 dsh-ide 的构建产物、插件目录与验证方式改写。

## 本机固定路径

| 项 | 值 |
|---|---|
| 项目根 | `E:\gitee\doyzheng\dsh-ide` |
| Kotlin 工程根（Gradle 工作目录） | `E:\gitee\doyzheng\dsh-ide`（仓库根） |
| 构建产物 | `build\distributions\dsh-idea-plugin-<version>.zip`（version 见 `build.gradle.kts`，当前 `0.8.0`） |
| 已安装插件目录 | `C:\Users\ZDY\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\dsh-idea-plugin` |
| 插件设置文件 | `C:\Users\ZDY\AppData\Roaming\JetBrains\IntelliJIdea2026.2\options\dsh-ide.xml`（component `DshIdeSettings`） |
| IDE 可执行文件 | `C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2\bin\idea64.exe` |
| IDE 日志 | `C:\Users\ZDY\AppData\Local\JetBrains\IntelliJIdea2026.2\log\idea.log` |
| JDK（构建） | `D:\apps\jdk\jdk-21.0.2` |

路径变了先 `ls "$APPDATA/JetBrains"`、`ls "$LOCALAPPDATA/JetBrains"` 确认，不要照抄。

## 0. 辅助脚本（`scripts/`）

| 脚本 | 用途 |
|---|---|
| `stop-ide.ps1` | 优雅关闭 IDE：`CloseMainWindow` + 轮询；IDEA 忽略第一次关闭时每 5 秒补发，超时才 `-Force` |
| `start-ide.ps1` | 启动 IDE 并打开 `dsh-idea-plugin` 工程；`-WaitSeconds 50` 等待启动 |
| `ide-capture.ps1` | 截图：`-Mode window\|region\|screen`；`-ToggleToolWindows` 先按 Ctrl+Shift+F12 |
| `ide-editor-menu.ps1` | 拖选代码 → 右键 → 截取菜单（Swing 弹出菜单只能用区域截图） |
| `ide-click.ps1` | 窗口内点击坐标，`-Right` 为右键 |
| `cdp-eval.mjs` | 在 DSH 页面的 JCEF CDP 里执行表达式（自动找 `DevToolsActivePort` 与带 `token=` 的页面） |
| `ide-win32.cs` | 上面 PowerShell 脚本共用的 P/Invoke 声明 |

所有 `.ps1` 的坐标都相对 **IDE 主窗口左上角**；先截图再传坐标。`.ps1` 必须保存为 UTF-8 with BOM
（PowerShell 5.1 读无 BOM 的 UTF-8 会按 ANSI 解析，中文注释可能截断字符串）。

## 1. 构建

```bash
cd /e/gitee/doyzheng/dsh-ide
export JAVA_HOME=/d/apps/jdk/jdk-21.0.2

# 迭代 Kotlin 代码时：只打包，跳过最慢的测试沙箱（本机约 15–30s）
./gradlew buildPlugin --offline -x test --console=plain

# 需要验证时再单独跑测试（本机约 2–3 分钟）
./gradlew test --offline

ls -la build/distributions/        # 确认 zip 名与字节数、mtime 晚于最后一次源码改动
```

- Git Bash 里默认 `java` 是 JDK 8，必须显式 `JAVA_HOME`，否则报 "This build uses a Java 8 JVM"。
- **必须加 `--offline`**：无外网时 IntelliJ Platform Gradle Plugin 会去访问 `jb.gg`，TLS 握手失败/超时
  会白等 20s+；平台产物已缓存在 `.intellijPlatform/localPlatformArtifacts`。
- **不要加 `--no-daemon`**：那样每次 fork 一次性 JVM，多 20–40s；保留 daemon 可复用热缓存。
- zip 内层结构是 `dsh-idea-plugin/lib/dsh-idea-plugin-<version>.jar`，解压到 `plugins/` 下即可。
- 版本号在 `build.gradle.kts` 与 `src/main/resources/META-INF/plugin.xml` 两处，升版本要一起改。

### 1.1 构建耗时参考（别误判“编译慢”）

源码就几个文件时，编译本身只几秒；耗时几乎都在 IntelliJ Platform 工具链上。本机实测：

| 命令 | 耗时 | 说明 |
|---|---|---|
| `buildPlugin --offline -x test`（UP-TO-DATE） | ~15s | 纯打包快路径 |
| `buildPlugin --offline -x test`（改了 Kotlin） | ~20–30s | 编译 + `instrumentCode` 插桩 + `prepareSandbox` |
| `test` | **2–3 分钟** | 大头：IntelliJ Platform 测试沙箱（`prepareTestSandbox` 加载整包 IDE），与源码行数无关 |
| 加 `--no-daemon` | +20–40s | 每次 fork 一次性 JVM |
| 不带 `--offline`（无外网） | +20s 起 | `jb.gg` TLS 握手失败/超时 |

结论：**迭代只看效果就用 `buildPlugin -x test` 并保留 daemon；不要把 `test` 和打包绑在每次构建里。**

**不跑 `runIde`**：本机直接替换已安装插件，效果与真实 IDE 一致，且省一次沙箱启动。

## 2. 关闭 IDE

Windows 下 IDE 运行时会锁住 `lib/*.jar`，覆盖会报“文件正在被另一个程序使用”；
JVM 也不会热重载已加载的类，只有重启才生效。

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/deploy-dsh-ide-plugin/scripts/stop-ide.ps1
```

- 优先让 IDE 自己退出（`CloseMainWindow`），这样 LAF、窗口布局等配置能写回。
- 本机实测：**第一次 `WM_CLOSE` 常被忽略**，脚本会每 5 秒补发一次；一般 1–2 分钟内退出。
- 退出前别信任上一次查到的 pid：启动过程中可能出现多个 `idea64`，判断退出要看
  `Get-Process idea64` 是否**全部**为空。
- 超时才兜底 `-Force`，强杀可能丢未保存内容。
- IDE 退出后确认插件子进程也退了：`Get-Process | Where-Object {$_.ProcessName -eq 'DeepSeek Harness'}`
  与 `Get-NetTCPConnection -State Listen -LocalPort 46231` 都应为空；有残留就结束它再替换。

## 3. 替换插件目录

```bash
PLUGINS="$APPDATA/JetBrains/IntelliJIdea2026.2/plugins"
BACKUP="$TEMP/dsh-idea-plugin-plugin-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP" && cp -r "$PLUGINS/dsh-idea-plugin/." "$BACKUP/"     # 回滚用
rm -rf "$PLUGINS/dsh-idea-plugin"
unzip -q build/distributions/dsh-idea-plugin-0.8.0.zip -d "$PLUGINS"
```

- **整个目录删掉再解压**，不要只覆盖差异：`lib` 下不同版本名的 jar 会同时被加载，容易类冲突。
- zip 根目录解出来必须是 `dsh-idea-plugin/`。
- 替换完核对：`unzip -p "$PLUGINS/dsh-idea-plugin/lib/dsh-idea-plugin-0.8.0.jar" META-INF/plugin.xml | grep -E "<id>|<name>|<version>"`。

## 4. 重启 IDE

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/deploy-dsh-ide-plugin/scripts/start-ide.ps1 -WaitSeconds 50
```

窗口出现约 30–45 秒，别在启动过程中抓状态。

## 5. 验证

**第一步看日志，确认加载了新 jar 且启动参数正确：**

```bash
grep -a "启动 DSH" "$LOCALAPPDATA/JetBrains/IntelliJIdea2026.2/log/idea.log" | tail -1
```

期望同时出现：

- `--profile idea`：`profiles/idea` 是指向桌面端 `desktop` 的 junction
- `--host 127.0.0.1 --port 46231 --no-open`
- `--patch ...dsh-plugin-jetbrains.patch.yml`：Kotlin 每次启动把 `dsh-plugin-jetbrains`
  注入 profile，并始终用 `--patch` 覆盖层挂载（插件在 devDependencies 里，官方两个插件列表都不显示）。

**第二步看界面：** 工具窗口内 JCEF 正常渲染 DSH；首次启动（无 cookie）应直接落到当前项目。

**第三步（按需）用 CDP 查页面状态：**

```bash
node .agents/skills/deploy-dsh-ide-plugin/scripts/cdp-eval.mjs "location.href"
node .agents/skills/deploy-dsh-ide-plugin/scripts/cdp-eval.mjs "window.__dshIdeComposerState()"
node .agents/skills/deploy-dsh-ide-plugin/scripts/cdp-eval.mjs "window.__dshIdeComposerReady === true"
```

`__dshIdeComposerState()` 返回输入框的 draft/rev/span，验证「添加到 DSH 对话」链路时用。

**第四步验证设置持久化：**

1. 在 DSH 设置里改主题色/语言；
2. 完全退出 IDE 再启动；
3. 设置应保持。检查 profile 的 `cordis.patch.yml` 里 `ui-theme` / `locale` 行仍在，
   注释、entry 顺序与同级键（如 `fontSize`）没有被重写。

## 常见故障

| 现象 | 原因 |
|---|---|
| 覆盖时报“文件正在被另一个程序使用” | IDE 没退干净或 DSH 子进程仍活着；先 `stop-ide.ps1` 并确认进程/端口 |
| 改了代码没变化 | jar 没替换成功，或 IDE 没真正重启（类不会热重载） |
| 工具窗口空白 / 报 JCEF 不支持 | JCEF 未启用或端口被占；看日志里 `[dsh]` 段 |
| 启动命令里 profile 不是预期 | `options/dsh-ide.xml` 的 `DshIdeSettings/profile` 值 |
| 主题改完重启回默认 | 检查 `cordis.patch.yml` 里 `locale` / `ui-theme` 的 `preference`，以及 `.dsh-ide-appearance.json` 初始化标记 |
| 右键「添加到 DSH 对话」无反应 | CDP 查 `__dshIdeComposerReady`（页面侧没就绪会先排队）；composer 正在提交（frozen）时该次插入会被跳过 |
| 插入的引用丢失 / 位置不对 | 确认 jar 与 profile 的 `client.js` 一致（启动时按内容覆盖同步）；不要手动只改 profile 里的副本 |
| 端口 46231 被占 | 桌面端或旧 IDE 残留；本插件会向上探测到 46232，origin 变化会丢 localStorage |
| 桌面端和 IDE 配置互相影响 | 两者共用 `profiles/desktop`；**不要同时运行**桌面端与 IDE |
| GUI 自动化报 "no visible window" | 绑到了已退出的旧 pid；按窗口标题重新绑定 |

## 边界

关闭 IDE 会中断用户当前工作。只有用户明确要求“部署 / 替换 / 重启 IDE”时才执行这套流程；
如果用户只是在问怎么部署，给步骤，不要动手关他的 IDE。
