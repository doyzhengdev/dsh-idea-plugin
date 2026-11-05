# Privacy Policy — DSH for Desktop

Last updated: 2026-11-05

DSH for Desktop is an open-source JetBrains IDE plugin that embeds the DeepSeek
Harness (DSH) web UI in a tool window. This policy describes what the plugin
does with data. It is hosted in the plugin repository; the same text is
referenced from the JetBrains Marketplace listing.

## Summary

- DSH for Desktop does **not** collect telemetry, analytics, or usage statistics.
- DSH for Desktop has **no** accounts and does not upload your code or prompts.
- The plugin connects to a DSH server running on your own machine
  (`127.0.0.1`), and — only after you approve it — downloads the official
  DeepSeek Harness desktop installer from `download.deepseek.com`.

## Data stored locally

All plugin data stays on your computer:

| Data | Location | Purpose |
| --- | --- | --- |
| DSH credentials, settings, session history | `~/.dsh` (shared with the official DeepSeek Harness desktop app) | So the IDE and the desktop app share one workspace |
| Injected DSH bridge plugin | `~/.dsh/profiles/<profile>/.dsh-ide/` | Adds the IDE bridge to the DSH web UI |
| Web UI localStorage snapshot | `~/.dsh/profiles/<profile>/web-storage/` | Keeps the selected session across IDE restarts |
| Plugin settings (profile name, external command) | IDE configuration directory (`options/dsh-ide.xml`) | Persist the tool window settings |

Deleting these directories removes the corresponding data. Uninstalling the
plugin does not delete `~/.dsh`, because the official desktop app uses it too.

## Network access

1. **Local DSH server.** The plugin starts (or reuses) a DSH process bound to
   `127.0.0.1` on a local port and loads the UI from it. Traffic to that local
   server never leaves your machine.
2. **Official installer download.** If the DeepSeek Harness desktop client is
   not installed, the plugin asks for your confirmation and then downloads the
   installer from `https://download.deepseek.com`. The download happens only
   after you approve it.
3. **No other endpoints.** The plugin itself does not send data to JetBrains,
   DeepSeek, or any third party.

The DSH web UI, which runs inside the tool window, is part of the DeepSeek
Harness product. Its own behavior (for example, model requests you send from
the chat) is governed by DeepSeek's terms and privacy policy, not by this
plugin.

## Permissions used by the plugin

- Runs a local child process (the DSH server) and stops it when the IDE exits.
- Reads the IDE UI language and theme to initialize DSH on first run.
- Opens files in the IDE and inserts text references into the DSH composer
  when you explicitly trigger those actions.

## Contact

Questions about this policy: open an issue in the plugin's public repository,
or contact the vendor address listed on the JetBrains Marketplace page.

---

# 隐私政策 — DSH for Desktop（中文）

DSH for Desktop 是开源 JetBrains IDE 插件，在工具窗口中嵌入 DeepSeek Harness（DSH）的 Web 界面。

- 不收集遥测、统计分析或使用数据；没有账号体系；不上传你的代码或提问内容。
- 只连接本机 `127.0.0.1` 上的 DSH 服务；仅在你确认后，才从 `download.deepseek.com` 下载官方客户端安装包。
- 本地数据包括：`~/.dsh`（与官方桌面端共享的凭据 / 设置 / 会话）、
  `<profile>/.dsh-ide/`（注入的 IDE 桥插件）、`<profile>/web-storage/`（页面快照）、
  IDE 配置目录里的 `options/dsh-ide.xml`（工具窗口设置）。删除这些目录即可清除对应数据；
  卸载插件不会删除 `~/.dsh`，因为官方桌面端也在使用它。
- 工具窗口内的 DSH 页面属于 DeepSeek Harness 产品，其中的模型请求等行为遵循 DeepSeek 的条款与隐私政策。
