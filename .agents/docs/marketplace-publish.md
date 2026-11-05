# 发布到 JetBrains Marketplace

> 目标：把 **DSH for Desktop**（插件 id `dev.doyzheng.dsh-ide`，工程名 `dsh-idea-plugin`）
> 发布到 [JetBrains Marketplace](https://plugins.jetbrains.com)。
> 规则依据：[Approval Guidelines v1.3（2026-03-31 生效）](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)、
> [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)。

## 1. 当前状态

| 项 | 状态 |
| --- | --- |
| 插件展示名 | `DSH for Desktop`（原 `DSH for JetBrains` 含 “JetBrains”，会被直接驳回，已改名） |
| 插件 id | `dev.doyzheng.dsh-ide`（不可再改，改了等于新插件） |
| 版本 | `0.8.0` |
| 签名 | 已用 `certificate/chain.crt` + `certificate/private.pem` 自签 |
| 产物 | `build/distributions/dsh-idea-plugin-0.8.0-signed.zip` |
| 许可证 | MIT（`LICENSE`） |
| 隐私政策 | `PRIVACY.md` |
| 兼容性检查 | `./gradlew verifyPluginStructure`（已通过）；`./gradlew verifyPlugin` 默认用本机安装的 IDEA 跑，CI 用 `-PverifyIdePath` 或 `ide("IC-2025.1")` |

## 2. 发布前必须由你提供的东西

Marketplace 的两步都需要**账号本人**操作，我无法代办：

1. **JetBrains Account**：<https://account.jetbrains.com> 注册（邮箱 + 密码）。
2. **Vendor 信息**（审核会逐个点开验证，必须真实可访问）：
   - 显示名：`doyzheng`（当前 plugin.xml 用的值，可改）
   - 官网 / 项目页 URL：`__________`（例如公开的 Gitee/GitHub 仓库地址）
   - 联系邮箱：`__________`
3. **公开源码仓库 URL**（MIT 开源必填）：`__________`
   - 本仓库目前没有 `git remote`，需要先推到公开仓库（Gitee 或 GitHub）。
4. **插件截图**（可选但强烈建议）：1280×800（16:10），1–5 张，不能含设备边框/广告。

## 3. 已完成 / 已整改的合规项

- [x] 插件名去掉 “JetBrains”，长度 11 字符，纯拉丁字符。
- [x] 英文描述（plugin.xml `<description>`），中文放在后面；含“与 JetBrains / DeepSeek 无隶属关系”的免责声明。
- [x] `<change-notes>` 填入真实内容（不再是占位符）。
- [x] 40×40 SVG 图标（`META-INF/pluginIcon.svg`，非模板默认图，不含 JetBrains 元素）。
- [x] MIT `LICENSE` + `PRIVACY.md`（无遥测、仅本地 127.0.0.1 与 download.deepseek.com）。
- [x] 未安装官方客户端时不再后台静默下载安装：新增 `NEED_CLIENT` 状态页，用户点击「下载并安装」才下载。
- [x] Gradle：`signPlugin` / `publishPlugin` / `pluginVerification` 已配置；私钥与 token 不入库。
- [x] 签名包生成并通过 `ZipSigningTool verify`。

## 4. 首次上传（必须手动）

1. 用 JetBrains Account 登录 <https://plugins.jetbrains.com>。
2. 打开 <https://plugins.jetbrains.com/author/me>，首次会让你 **接受 Marketplace Developer Agreement**（法律协议，只能本人同意）。
3. 点 **Add new plugin**，填表：

   | 字段 | 填什么 |
   | --- | --- |
   | Plugin name | `DSH for Desktop` |
   | Plugin ID | `dev.doyzheng.dsh-ide` |
   | Upload plugin | `build/distributions/dsh-idea-plugin-0.8.0-signed.zip` |
   | License | MIT（Open source） |
   | Source code URL | 你的公开仓库地址 |
   | Vendor | `doyzheng` + 官网 URL + 邮箱 |
   | Category | `AI`（或 `Tools Integration`） |
   | Tags | `deepseek`, `ai`, `chat`, `harness` |
   | Privacy policy | 仓库内 `PRIVACY.md` 的公开链接 |
   | EULA | MIT 即可（Marketplace 允许用开源许可证作为 EULA） |
   | Trader 声明 | 开源免费项目选 **non-trader** |

4. 提交后进入人工审核，通常 **3–4 个工作日**（超时可邮件 <marketplace@jetbrains.com>）。
5. 审核期间插件状态为 `Pending`；通过后自动出现在 Marketplace 与 IDE 内置市场。

> 本机网络限制：JetBrains 的 IDE 发行包 CDN（download-cdn.clf.jetbrains.com.cn）在本机返回 404，
> 所以 `recommended()` / `ide("IC-…")` 都下不下来；本机改用 `local("C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.2")`。
> 在能正常访问 GitHub/CDN 的 CI（如 GitHub Actions）里应改用多版本：`ides { ide("IC-2024.3"); ide("IC-2025.1") }`。

## 5. 后续版本发布（Gradle 自动化）

首次手动上传成功后，取 token：<https://plugins.jetbrains.com/author/me/tokens> → `Generate Token`（只显示一次）。

```bash
cd /e/gitee/doyzheng/dsh-ide
export JAVA_HOME=/d/apps/jdk/jdk-21.0.2

# 改 build.gradle.kts 的 version 与 plugin.xml 的 version 后：
ORG_GRADLE_PROJECT_intellijPlatformPublishingToken=<token> \
  ./gradlew signPlugin publishPlugin --console=plain
```

- 不要加 `--offline`（签名 CLI 与平台依赖需要在线解析）。
- token 只放在环境变量里，不要写进 `gradle.properties` 或提交历史。
- 同一版本号不能重复上传，发版前先 bump。

## 6. 审核风险与应对（按可能性排序）

| 风险 | 审核条款 | 现状 / 应对 |
| --- | --- | --- |
| 自动下载并安装第三方程序 | 2.2.a 安全/隐私 | 已改为用户点击确认后才下载；描述与隐私政策均声明下载源 |
| 第三方商标 “DeepSeek Harness” | 1.2.c / 1.8.b | 名称不含商标；描述为事实性说明并声明无隶属关系；如被要求，可改为 “the DSH desktop app” |
| 插件名 “DSH” 缩写是否算商标 | 1.2.c | 风险低；备选名 `Harness Dock` |
| 无 vendor 官网/邮箱 | 1.3.a | **待你提供**，否则一定驳回 |
| 无公开源码链接 | 3.3 | **待你推公开仓库** |
| 无截图 | 1.4.c/1.6 | 建议补 1–3 张 1280×800 |
| 未签名 | 发布文档要求 | 已签名 |
| 二进制兼容 | 2.1.c | 每次上传跑 `./gradlew verifyPlugin` |

## 7. 常见驳回原因速查

- 名称含 “JetBrains / IntelliJ / Plugin” → 已规避。
- 描述只有中文 → 已改为英文优先。
- change notes 是模板占位符 → 已填真实内容。
- vendor 邮箱/网站打不开 → 上传前自己点一遍。
- 图标与模板默认图标相同 → 当前图标为自定义 SVG。
- 插件内出现未声明的网络请求 → 已声明 `download.deepseek.com`。
