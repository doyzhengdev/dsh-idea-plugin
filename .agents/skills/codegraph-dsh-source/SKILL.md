---
name: codegraph-dsh-source
description: 分析或查找 DSH 官方源码（E:\github\deepseek-harness）时，优先用 codegraph 代码图谱而不是 grep。当需要定位实现、追调用链、评估改动影响、回答“DSH 是怎么做 X 的”、或核对某个行为在源码里的位置时，使用本 skill。只查 README/YAML/配置文本、或 codegraph 无结果时，再退回 grep/Read。
---

# 用 codegraph 查 DSH 官方源码

官方源码目录 `E:\github\deepseek-harness`（0.2.2-alpha.3）已建好 codegraph 索引。
分析实现、找符号、追调用链时**先用 codegraph**，不要一上来 `grep -r`。

## 固定路径

| 项 | 值 |
|---|---|
| 源码根 | `E:\github\deepseek-harness` |
| codegraph CLI | `C:\Users\ZDY\scoop\persist\nvm\nodejs\v22.23.2\codegraph.cmd`（v1.6.2） |
| 索引库 | `E:\github\deepseek-harness\.codegraph\codegraph.db`（约 733 MB） |
| 索引规模 | 10,248 文件 / 143,480 节点 / 513,769 边 |
| 包装脚本 | `.agents/skills/codegraph-dsh-source/scripts/cg.sh`（自动带 `-p`） |

索引是 2026-11-05 建的。源码 `git pull` 后先 `sync`，否则查到的可能是旧位置。

## 命令速查

包装脚本已固定源码路径，参数原样传给 codegraph：

```bash
CG=.agents/skills/codegraph-dsh-source/scripts/cg.sh

$CG query "SettingsForms" -l 5                  # 按名字搜符号（类/函数/方法/接口）
$CG query "patch" -k function -l 20             # 按 kind 过滤
$CG explore "settings persisted to cordis.patch.yml"   # 一个主题的源码 + 调用路径
$CG context "how does the theme setting persist" -f markdown   # 给任务生成上下文
$CG node "SettingsForms"                        # 单个符号的源码 + 调用者/被调用者
$CG node --file packages/settings/settings/src/index.ts --offset 378 --limit 60  # 按文件读（带行号 + 依赖）
$CG callers "write"                             # 谁调用了它
$CG callees "SettingsForms"                     # 它调用了谁
$CG impact "SettingsForms" -d 3                 # 改它会影响哪些代码
$CG files --filter packages/settings --max-depth 2
```

维护命令（不经包装脚本，`path` 用位置参数）：

```bash
CG="C:/Users/ZDY/scoop/persist/nvm/nodejs/v22.23.2/codegraph.cmd"
"$CG" status "E:/github/deepseek-harness"    # 文件/节点/边统计
"$CG" sync   "E:/github/deepseek-harness"    # 增量同步（pull 之后）
"$CG" index  "E:/github/deepseek-harness"    # 全量重建（怀疑索引坏了才用）
"$CG" unlock "E:/github/deepseek-harness"    # 索引被中断留下锁时
"$CG" init -y "E:/github/deepseek-harness"   # 首次建索引（已建过，不要重复跑）
```

## 推荐工作流

1. **定位概念**：`explore` 或 `context`，一次拿到相关符号的源码与调用路径。
2. **落到符号**：`query` 缩小到具体类/函数；输出形如
   `packages/settings/settings/src/index.ts:223`，拼上源码根就是绝对路径。
3. **读实现**：`node "<符号>"` 看源码与调用关系；需要精确行号时用
   `node --file <相对路径> --offset <行> --limit <行数>`。
4. **评估影响**：改 DSH 行为前用 `impact -d 3` / `callers` 确认波及面。
5. **交叉验证**：关键结论回到 `Read` 看原文；codegraph 的摘要不能替代源码。

## 已知边界

- **12 个 `client.ts` 解析出 0 符号**（`packages/*/*/src/client.ts`，tree-sitter 解析报错）。
  这些文件退回 `rg` + `Read`。
- 索引只覆盖**源码**；README、`cordis.patch.yml`、JSON 配置、测试快照等文本用 `rg` 更直接。
- `explore/context` 输出较长，先看结论与文件清单，再按需 `node` 细读。
- 官方源码是**只读参考**：不要在里面改文件、跑格式化或提交。
- 路径都相对于源码根；`-p` 由包装脚本带上，换目录不影响结果。

## MCP 形态（可选）

ZCode 配置里已有 `mcp/servers/codegraph`，但 `enabled: false`，且 `args` 指向的
`...\.nodejs\node_modules\@colbymchenry\codegraph\npm-shim.js` 在本机不存在。
要用 MCP 工具（`codegraph_explore` / `codegraph_node`）时改成：

```json
{
  "type": "stdio",
  "command": "C:\\Users\\ZDY\\scoop\\persist\\nvm\\nodejs\\v22.23.2\\node.exe",
  "args": [
    "C:\\Users\\ZDY\\scoop\\persist\\nvm\\nodejs\\v22.23.2\\node_modules\\@colbymchenry\\codegraph\\npm-shim.js",
    "serve", "--mcp"
  ],
  "enabled": true
}
```

改完需要新会话才会加载；CLI 方式在本会话内始终可用。

## 与 dsh-ide 仓库的关系

- 本 skill 只服务**官方源码分析**。`E:\gitee\doyzheng\dsh-ide` 自己没建索引（代码量小，
  `rg` + `Read` 足够）；需要时再 `codegraph init -y "E:/gitee/doyzheng/dsh-ide"`。
- 从官方源码得出的结论写进 `.agents/docs/architecture.md` 时，保留 `packages/...:行号` 形式的出处。
