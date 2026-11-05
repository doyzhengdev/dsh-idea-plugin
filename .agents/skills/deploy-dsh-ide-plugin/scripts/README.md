# deploy-dsh-ide-plugin / scripts

部署与验证 DSH for JetBrains 时反复用到的小工具。**优先用这里的脚本，不要每次临时重写。**

所有 `.ps1` 里的坐标都是相对 **IDE 主窗口左上角**；先用 `ide-capture.ps1` 截一张图，
按截图里的位置传 `-X` / `-Y`。

| 脚本 | 作用 |
|---|---|
| `stop-ide.ps1` | 优雅关闭 IDE（`CloseMainWindow`），等待进程消失；IDEA 忽略第一次关闭时每 5 秒补发，超时才允许 `-Force` 强杀 |
| `start-ide.ps1` | 启动 IDE 并打开 `E:\gitee\doyzheng\dsh-ide`；可选 `-WaitSeconds` 等待启动 |
| `ide-capture.ps1` | 截图：`-Mode window`（PrintWindow）/ `region`（窗口矩形区域）/ `screen`（整屏）；`-ToggleToolWindows` 先按 Ctrl+Shift+F12 |
| `ide-editor-menu.ps1` | 在编辑器里拖选一段代码 → 右键 → 截取菜单（用区域截图，能截到 Swing 弹出菜单） |
| `ide-click.ps1` | 在窗口内点一个坐标，`-Right` 为右键；用于点菜单项 |
| `cdp-eval.mjs` | 在 DSH 页面的 JCEF CDP 里执行表达式（自动找 `DevToolsActivePort` 与带 `token=` 的页面） |
| `ide-win32.cs` | 上面 PowerShell 脚本共用的 P/Invoke 声明 |

## 典型流程

```bash
cd /e/gitee/doyzheng/dsh-ide
JAVA_HOME=/d/apps/jdk/jdk-21.0.2 ./gradlew buildPlugin --console=plain

powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/deploy-dsh-ide-plugin/scripts/stop-ide.ps1

PLUGINS="$APPDATA/JetBrains/IntelliJIdea2026.2/plugins"
rm -rf "$PLUGINS/dsh-idea-plugin"
unzip -q build/distributions/dsh-idea-plugin-0.8.0.zip -d "$PLUGINS"

powershell -NoProfile -ExecutionPolicy Bypass -File .agents/skills/deploy-dsh-ide-plugin/scripts/start-ide.ps1 -WaitSeconds 50

# 验证启动命令（profile / --patch / 端口）
grep -a "启动 DSH" "$LOCALAPPDATA/JetBrains/IntelliJIdea2026.2/log/idea.log" | tail -1

# 验证页面（JCEF CDP）
node .agents/skills/deploy-dsh-ide-plugin/scripts/cdp-eval.mjs "location.href"
```

## 踩坑记录

- **IDEA 会忽略第一次 `WM_CLOSE`**：本机实测要发第二次才退出，`stop-ide.ps1` 已改成每 5 秒补发。
- **`PrintWindow` 截不到右键菜单/对话框**：Swing 弹出菜单是独立窗口，用 `-Mode window` 看不到；
  改用 `ide-editor-menu.ps1` 或 `-Mode region`。
- **`SendKeys` 不一定送进 IDE**：`Ctrl+Shift+A`（Find Action）在本机被输入法/键位吞掉过一次。
  需要可靠交互时用鼠标（`ide-editor-menu.ps1` + `ide-click.ps1`），并先校验前台窗口。
- **别把鼠标点到编辑器最左侧几像素**：那里是断点槽，右键会弹「断点」对话框而不是上下文菜单。
- **点菜单项要点在菜单内部**：`ide-click.ps1` 的 x 要比菜单左边缘再往右约 100px。
- **`ide-click.ps1` 不会主动抢焦点**：焦点切换会关闭已打开的弹出菜单；调用前先让 IDE 处于前台。
- **PowerShell 5.1 读无 BOM 的 UTF-8 脚本会按 ANSI 解析**：本目录的 `.ps1` 都已带 UTF-8 BOM；
  新增脚本时也要保存为 UTF-8 with BOM。

## 与 zcode-ide 版本的差异

- `ProjectPath` 默认改为 `E:\gitee\doyzheng\dsh-ide`；
- `stop-ide.ps1` 增加「每 5 秒补发 `CloseMainWindow`」；
- `cdp-eval.mjs` 的说明改为 DSH 页面（选页逻辑不变：`127.0.0.1:<port>` 且 URL 带 `token=`）；
- 未复制 `cdp-clear-composer.mjs`（ZCode 的 Lexical 输入框专用，DSH 不需要）。
