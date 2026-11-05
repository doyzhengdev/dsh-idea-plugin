/**
 * DSH for JetBrains —— Host 半边（空入口）。
 *
 * 这个插件的能力全在浏览器半边（`src/client.js`：ideWorkspace / 打开文件 / 插入引用 / 侧栏过滤）。
 * 把插件注入到 DSH profile 的工作由 IDEA 插件的 `DshBundleBootstrap` 在每次 IDE 启动时完成
 * （拷贝文件到 node_modules；profile 未登记 bundle 时写 `--patch` 覆盖层），因此这里**不需要任何
 * 宿主逻辑**。
 *
 * 保留这个模块（`name` + 空 `apply`）只是为了给 Cordis 一个合法的 Loader entry——
 * `dsh.client` 声明的浏览器 bundle 依附于它。
 */

export const name = 'dsh-plugin-jetbrains'

export function apply() {}
