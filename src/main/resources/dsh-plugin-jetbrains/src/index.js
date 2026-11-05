/**
 * DSH for JetBrains —— Host 半边。
 *
 * 负责两件事：
 *  1. 浏览器 localStorage 跨端口快照：
 *     - `GET  /dsh-ide/storage/load` → 返回当前 profile 的 localStorage 快照；
 *     - `POST /dsh-ide/storage/save` → 原子写入快照。
 *     快照存 `<profile.dir>/web-storage/<profile>.json`，按 profile 而不是按 origin，
 *     所以即使 DSH 换了端口，Client 半边也能在页面启动早期把 UI 状态写回当前 origin。
 *  2. 首次启动对齐 IDEA 语言/主题：读取 Kotlin 注入的 `DSH_IDE_LOCALE` / `DSH_IDE_THEME`，
 *     通过官方 `configEditor` 写入 profile 用户层（保留注释与同级配置）。
 *
 * 其他 IDE 桥逻辑在 `src/client.js`（浏览器半边）。
 */

import { promises as fs } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

const STORAGE_LOAD_PATH = '/dsh-ide/storage/load'
const STORAGE_SAVE_PATH = '/dsh-ide/storage/save'
const MAX_BODY_BYTES = 4 * 1024 * 1024
const APPEARANCE_MARKER = '.dsh-ide-appearance.json'
const APPEARANCE_TIMEOUT_MS = 15000
const APPEARANCE_POLL_MS = 200

export const name = 'dsh-plugin-jetbrains'
export const inject = ['webServer', 'connection']

const serviceOf = (ctx, name) => {
  try {
    return ctx.get(name)
  } catch {
    return undefined
  }
}

const sendJson = (res, status, value) => {
  res.statusCode = status
  res.setHeader('content-type', 'application/json; charset=utf-8')
  res.setHeader('cache-control', 'no-store')
  res.end(JSON.stringify(value))
}

const rejectUntrusted = (ctx, req, res) => {
  const connection = serviceOf(ctx, 'connection')
  if (connection === undefined || typeof connection.requestRejection !== 'function') return false
  const rejection = connection.requestRejection(req)
  if (rejection === undefined) return false
  res.statusCode = rejection
  res.end()
  return true
}

const readBoundedBody = async (req) => {
  const chunks = []
  let total = 0
  for await (const chunk of req) {
    total += chunk.length
    if (total > MAX_BODY_BYTES) throw new Error(`body too large (> ${MAX_BODY_BYTES} bytes)`)
    chunks.push(chunk)
  }
  return Buffer.concat(chunks).toString('utf8')
}

const storageLocationOf = (ctx) => {
  const profile = serviceOf(ctx, 'profileContext')
  const home = profile && typeof profile.home === 'string' ? profile.home
    : (process.env.DSH_HOME || join(homedir(), '.dsh'))
  const profileName = profile && typeof profile.name === 'string' && profile.name !== '' ? profile.name : 'idea'
  const safeName = profileName.replace(/[^A-Za-z0-9._-]/g, '_')
  // profile.dir 由 launcher 提供（~/.dsh/profiles/<profile>）；缺失时按同样布局拼一个。
  const profileDir = profile && typeof profile.dir === 'string' && profile.dir !== ''
    ? profile.dir
    : join(home, 'profiles', safeName)
  return join(profileDir, 'web-storage', `${safeName}.json`)
}

const validStoragePayload = (value) => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined
  const out = {}
  for (const [key, val] of Object.entries(value)) {
    if (typeof key !== 'string' || typeof val !== 'string') return undefined
    out[key] = val
  }
  return out
}

const registerRoute = (ctx, route, label) => {
  try {
    if (typeof ctx.effect === 'function') {
      ctx.effect(() => ctx.webServer.register(route), label)
    } else {
      ctx.webServer.register(route)
    }
  } catch (error) {
    console.warn('[dsh-ide] 注册存储路由失败：' + label, error)
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** IDEA 通过环境变量下发的首次启动偏好（locale / ui-theme）；未下发返回空。 */
const idePreferences = () => [
  ['locale', process.env.DSH_IDE_LOCALE],
  ['ui-theme', process.env.DSH_IDE_THEME],
].filter(([, value]) => typeof value === 'string' && value !== '')

/**
 * 首次启动把 IDEA 的语言/主题写进 profile 用户层。
 *
 * 为什么不用字符串改 YAML：profile 的 `cordis.patch.yml` 由 DSH 的 configEditor 维护，
 * 会包含注释、`!!js` 表达式、与 preference 同级的其他键（如 fontSize）。官方
 * `configEditor.edit()` 基于 yaml 文档模型做局部修改，并在 Loader 热重载锁内串行化，
 * 是唯一不会破坏格式/并发的写入口。
 *
 * 只补缺、不覆盖：
 *  - 有 `.dsh-ide-appearance.json` 标记 → 已初始化过，直接返回；
 *  - 用户/桌面端已在 profile 里显式写过 `preference` → 尊重，不改；
 *  - 目标 entry 在 boot 阶段还没激活 → 轮询重试，超时则下次启动再试（不写标记）。
 *
 * @returns 是否完成了初始化（或没有需要做的事）。
 */
export async function initIdeAppearance(ctx) {
  const targets = idePreferences()
  if (targets.length === 0) return false

  const profile = serviceOf(ctx, 'profileContext')
  const dir = profile && typeof profile.dir === 'string' && profile.dir !== '' ? profile.dir : undefined
  if (dir === undefined) return false
  const marker = join(dir, APPEARANCE_MARKER)
  try {
    await fs.access(marker)
    return false
  } catch {
    // 还没有初始化标记，继续。
  }

  const pending = new Map(targets)
  const deadline = Date.now() + APPEARANCE_TIMEOUT_MS
  while (pending.size > 0 && Date.now() < deadline) {
    const editor = serviceOf(ctx, 'configEditor')
    if (editor === undefined || typeof editor.configuration !== 'function' || typeof editor.edit !== 'function') {
      await sleep(APPEARANCE_POLL_MS)
      continue
    }
    let configuration
    try {
      configuration = editor.configuration()
    } catch {
      configuration = undefined
    }
    let progressed = false
    if (Array.isArray(configuration)) {
      for (const [id, value] of [...pending]) {
        const row = configuration.find((item) => item && item.entry
          && item.entry.options && item.entry.options.id === id)
        if (row === undefined) continue
        const override = row.override && typeof row.override === 'object' ? row.override : {}
        if (typeof override.preference === 'string' && override.preference !== '') {
          // 用户或桌面端已显式设置过：不再覆盖。
          pending.delete(id)
          continue
        }
        try {
          await editor.edit(row.entry, (current) => ({ ...current, preference: value }))
          pending.delete(id)
          progressed = true
        } catch {
          // entry 还没激活，或正在 reconcile：下一轮再试。
        }
      }
    }
    if (pending.size === 0) break
    if (!progressed) await sleep(APPEARANCE_POLL_MS)
  }

  if (pending.size > 0) {
    console.warn('[dsh-ide] 语言/主题初始化未完成，下次启动重试：' + [...pending.keys()].join(', '))
    return false
  }
  try {
    await fs.writeFile(marker, JSON.stringify({
      locale: process.env.DSH_IDE_LOCALE ?? '',
      theme: process.env.DSH_IDE_THEME ?? '',
      appliedAt: new Date().toISOString(),
    }) + '\n', 'utf8')
  } catch (error) {
    // 标记写不进去只影响下次启动会再判断一遍（已有 preference 会被跳过），不影响本次结果。
    console.warn('[dsh-ide] 写入外观初始化标记失败：' + marker, error)
  }
  console.info('[dsh-ide] 已按 IDEA 配置初始化 DSH 语言/主题')
  return true
}

export function apply(ctx) {
  const storageFile = storageLocationOf(ctx)

  registerRoute(ctx, {
    kind: 'exact',
    path: STORAGE_LOAD_PATH,
    handler: async (req, res) => {
      if (rejectUntrusted(ctx, req, res)) return
      if (req.method !== 'GET') {
        sendJson(res, 405, { error: 'method not allowed' })
        return
      }
      try {
        const text = await fs.readFile(storageFile, 'utf8')
        const parsed = JSON.parse(text)
        const storage = validStoragePayload(parsed && parsed.storage) ?? {}
        sendJson(res, 200, { storage })
      } catch (error) {
        if (error && error.code === 'ENOENT') {
          sendJson(res, 200, { storage: {} })
        } else {
          console.warn('[dsh-ide] 读取 localStorage 快照失败：' + storageFile, error)
          sendJson(res, 500, { error: 'read failed' })
        }
      }
    },
  }, `dsh-ide: GET ${STORAGE_LOAD_PATH}`)

  registerRoute(ctx, {
    kind: 'exact',
    path: STORAGE_SAVE_PATH,
    handler: async (req, res) => {
      if (rejectUntrusted(ctx, req, res)) return
      if (req.method !== 'POST') {
        sendJson(res, 405, { error: 'method not allowed' })
        return
      }
      let body
      try {
        body = JSON.parse(await readBoundedBody(req))
      } catch (error) {
        sendJson(res, 400, { error: 'invalid json: ' + (error && error.message ? error.message : String(error)) })
        return
      }
      const storage = validStoragePayload(body && body.storage)
      if (storage === undefined) {
        sendJson(res, 400, { error: 'storage must be an object of string values' })
        return
      }
      try {
        await fs.mkdir(dirname(storageFile), { recursive: true })
        const tmp = `${storageFile}.tmp-${process.pid}-${Date.now()}`
        await fs.writeFile(tmp, JSON.stringify({ version: 1, updatedAt: new Date().toISOString(), storage }), 'utf8')
        try {
          await fs.rename(tmp, storageFile)
        } catch (error) {
          // Windows 某些文件系统 rename 覆盖已存在文件会失败：删掉旧文件再试一次。
          if (error && (error.code === 'EEXIST' || error.code === 'EPERM')) {
            await fs.rm(storageFile, { force: true })
            await fs.rename(tmp, storageFile)
          } else {
            throw error
          }
        }
        res.statusCode = 204
        res.end()
      } catch (error) {
        console.warn('[dsh-ide] 写入 localStorage 快照失败：' + storageFile, error)
        sendJson(res, 500, { error: 'write failed' })
      }
    },
  }, `dsh-ide: POST ${STORAGE_SAVE_PATH}`)

  console.info('[dsh-ide] localStorage 快照文件：' + storageFile)
  // 不阻塞插件激活：首次启动时异步等 configEditor / 目标 entry 就绪后写入偏好。
  void initIdeAppearance(ctx).catch((error) => console.warn('[dsh-ide] 初始化语言/主题失败', error))
}
