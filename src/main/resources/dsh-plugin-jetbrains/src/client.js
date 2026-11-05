/**
 * DSH for JetBrains —— Client 半边（浏览器，纯 IDE 桥，无 UI / 无 React）。
 * 职责：ideWorkspace 选中当前项目、文件在 IDE 打开、IDE 右键 @引用 插入输入框。
 * 依赖 Kotlin 注入的 window.__dshIdeOpenFile / window.__dshIdePush。
 * 单文件拆类：WorkspaceFilter / FileOpenBridge / ComposerBridge / WorkspaceActivator / IdeBridge。
 */
window.__ModuleLoader__.load({
  id: 'dsh-plugin-jetbrains',
  factory: function () {
    const LOG = '[dsh-ide]'
    const PARAM = 'ideWorkspace'
    const FLAG = '__dshIdeWorkspaceActivated'
    const READY_TIMEOUT_MS = 30000
    const OPEN_FILE_BRIDGE = '__dshIdeOpenFile'
    const FILE_ADDRESS_PREFIX = 'dsh-resource://file/'
    const REVIEW_ADDRESS_PREFIX = 'dsh-resource://changes-review/session/'
    const CHANGES_SUMMARY_ROUTE = 'api.changes.summary'
    const INSERT_REFS_BRIDGE = '__dshIdeInsertRefs'
    const FILTER_STYLE_ID = 'dsh-ide-hide-workspaces'
    const HIDE_ATTR = 'data-dsh-ide-hide-workspace'
    const GROUP_SELECTOR = '[class*="groupSection"]'
    const ROW_KEY_PREFIX = 'workspace:'
    const STORAGE_LOAD = '/dsh-ide/storage/load'
    const STORAGE_SAVE = '/dsh-ide/storage/save'
    const STORAGE_PERSIST_DELAY_MS = 600
    const SETTINGS_OPEN_TEXTS = new Set(['打开配置文件', 'Open configuration file'])

    // ---------- 纯工具 ----------
    const readWorkspaceParam = () => {
      try {
        const url = new URL(window.location.href)
        const hash = new URLSearchParams(url.hash.replace(/^#/, '')).get(PARAM)
        if (hash !== null && hash !== '') return hash
        const q = url.searchParams.get(PARAM)
        return q === null || q === '' ? undefined : q
      } catch { return undefined }
    }
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
    const waitUntil = async (p) => { const end = Date.now() + READY_TIMEOUT_MS; while (Date.now() < end) { if (p()) return true; await sleep(100) } return p() }
    const persistedSessionId = () => {
      try {
        const raw = window.localStorage.getItem('dsh.sessions.current')
        if (raw === null) return undefined
        const v = JSON.parse(raw)
        return v && typeof v === 'object' && typeof v.sessionId === 'string' ? v.sessionId : undefined
      } catch { return undefined }
    }
    const belongsToWorkspace = (snap, sessions, wsId, sid) => {
      if (sid === undefined || sessions.byId[sid] === undefined) return false
      if (snap.archivedSessionIds.indexOf(sid) >= 0) return false
      const ws = snap.items.find((i) => i.workspaceId === wsId)
      return ws !== undefined && ws.sessionIds.indexOf(sid) >= 0
    }
    const isAbsolutePath = (p) => /^[A-Za-z]:[\/]/.test(p) || /^\\/.test(p) || p.charAt(0) === '/'
    const decodeSegment = (s) => { try { return decodeURIComponent(s) } catch { return s } }
    const normalizePath = (v) => String(v).replace(/\\/g, '/')
    const relativeRefPath = (path, cwd) => {
      const full = normalizePath(path)
      if (typeof cwd !== 'string' || cwd === '') return full
      const root = normalizePath(cwd).replace(/\/+$/, '')
      if (root === '' || full === root) return full === root ? '' : full
      return full.toLowerCase().indexOf(root.toLowerCase() + '/') === 0 ? full.slice(root.length + 1) : full
    }
    const mentionOf = (path, kind) => {
      const v = kind === 'directory' ? path.replace(/\/+$/, '') + '/' : path
      if (/[\u0000-\u001f\u007f-\u009f"]/u.test(v)) return undefined
      return /\s/u.test(v) ? '@"' + v + '"' : '@' + v
    }
    const labelOf = (v, kind) => {
      const clean = v.replace(/\/+$/, '')
      const name = clean.slice(clean.lastIndexOf('/') + 1) || clean
      return kind === 'directory' ? name + '/' : name
    }
    const lineSuffix = (ref) => {
      const start = typeof ref.startLine === 'number' && isFinite(ref.startLine) ? Math.floor(ref.startLine) : 0
      if (start <= 0) return ''
      let end = typeof ref.endLine === 'number' && isFinite(ref.endLine) ? Math.floor(ref.endLine) : start
      if (end < start) end = start
      return end > start ? '(L' + start + '-L' + end + ') ' : '(L' + start + ') '
    }

    // =========================================================================================
    // localStorage 跨端口快照
    // =========================================================================================
    class StorageSnapshot {
      constructor() {
        this.installed = false
        this.timer = undefined
        this.pending = false
        this.restoredCount = 0
      }

      /** 同步拉取 Host 快照并回填当前 origin；页面启动越早执行越好。 */
      loadSync() {
        try {
          if (typeof XMLHttpRequest !== 'function' || typeof window.localStorage === 'undefined') return
          const xhr = new XMLHttpRequest()
          xhr.open('GET', STORAGE_LOAD, false)
          xhr.send(null)
          if (xhr.status !== 200) return
          const payload = JSON.parse(xhr.responseText)
          const storage = payload && payload.storage
          if (storage === null || typeof storage !== 'object' || Array.isArray(storage)) return
          Object.keys(storage).forEach((key) => {
            const value = storage[key]
            if (typeof key !== 'string' || typeof value !== 'string') return
            try {
              // 当前 origin 已有的新值优先，不被旧快照覆盖。
              if (window.localStorage.getItem(key) === null) {
                window.localStorage.setItem(key, value)
                this.restoredCount++
              }
            } catch { /* 单条失败不影响其他键 */ }
          })
          if (this.restoredCount > 0) console.info(LOG, '已恢复 localStorage 快照 ' + this.restoredCount + ' 项')
        } catch (error) {
          console.warn(LOG, '恢复 localStorage 快照失败（继续正常启动）', error)
        }
      }

      snapshot() {
        const out = {}
        try {
          const storage = window.localStorage
          for (let i = 0; i < storage.length; i++) {
            const key = storage.key(i)
            if (key === null) continue
            const value = storage.getItem(key)
            if (value !== null) out[key] = value
          }
        } catch { /* 存储不可用时返回空快照 */ }
        return out
      }

      schedule() {
        this.pending = true
        if (this.timer !== undefined) return
        this.timer = setTimeout(() => {
          this.timer = undefined
          this.flush(false)
        }, STORAGE_PERSIST_DELAY_MS)
      }

      flush(useBeacon) {
        if (!this.pending) return
        const body = JSON.stringify({ storage: this.snapshot() })
        try {
          if (useBeacon && typeof navigator.sendBeacon === 'function') {
            const sent = navigator.sendBeacon(STORAGE_SAVE, new Blob([body], { type: 'application/json' }))
            if (sent) { this.pending = false; return }
          }
          void fetch(STORAGE_SAVE, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body,
            credentials: 'same-origin',
            keepalive: true,
          }).then((res) => { if (res.ok || res.status === 204) this.pending = false }).catch(() => {})
        } catch { /* 忽略；下次操作还会再试 */ }
      }

      install() {
        if (this.installed) return
        this.installed = true
        this.loadSync()

        const self = this
        const originalSet = Storage.prototype.setItem
        const originalRemove = Storage.prototype.removeItem
        const originalClear = Storage.prototype.clear
        try {
          Storage.prototype.setItem = function (key, value) {
            const result = originalSet.apply(this, arguments)
            if (this === window.localStorage) self.schedule()
            return result
          }
          Storage.prototype.removeItem = function (key) {
            const result = originalRemove.apply(this, arguments)
            if (this === window.localStorage) self.schedule()
            return result
          }
          Storage.prototype.clear = function () {
            const result = originalClear.apply(this, arguments)
            if (this === window.localStorage) self.schedule()
            return result
          }
        } catch (error) {
          console.warn(LOG, '无法监听 localStorage 变化，快照只做启动恢复', error)
        }

        try {
          window.addEventListener('beforeunload', () => self.flush(true))
          document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') self.flush(true) })
        } catch { /* 事件注册失败就不做退出前兜底 */ }
        // 诊断入口：CDP / 控制台可手动触发保存或查看快照。
        window.__dshIdeStorageSnapshot = () => self.snapshot()
        window.__dshIdeStorageFlush = () => self.flush(false)
        console.info(LOG, 'localStorage 跨端口快照已启用')
      }
    }

    // =========================================================================================
    // 外部链接 → 系统浏览器
    // =========================================================================================
    const isExternalHttpUrl = (url) => {
      if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return false
      try { return new URL(url).origin !== window.location.origin } catch { return false }
    }

    class ExternalLinkBridge {
      open(url) {
        const bridge = window.__dshIdeOpenExternal
        if (typeof bridge !== 'function' || !isExternalHttpUrl(url)) return false
        try { bridge(url); return true } catch (error) { console.warn(LOG, '打开外部链接失败', error); return false }
      }

      installGlobal() {
        // DSH 的 openExternalLink 默认走 window.open(url, '_blank')；JCEF 会开成内置窗口。
        // 这里改成交给 IDE，由 Kotlin 用系统默认浏览器打开（失败再回退内置 JCEF）。
        const originalOpen = window.open
        const self = this
        window.open = function (url, target, features) {
          // 登录流程先 window.open('about:blank')，拿到 attempt 后再 popup.location.replace(授权地址)。
          // 用代理 window 把后续 replace 的授权地址交给系统浏览器，避免落到内置 JCEF。
          if (url === 'about:blank') {
            return {
              closed: false,
              opener: null,
              location: {
                replace: function (nextUrl) {
                  if (!self.open(nextUrl) && typeof originalOpen === 'function') {
                    try { originalOpen.call(window, nextUrl, '_blank', 'noopener,noreferrer') } catch { /* ignore */ }
                  }
                },
              },
              close: function () { this.closed = true },
              focus: function () {},
              blur: function () {},
            }
          }
          if (self.open(url)) return null
          return typeof originalOpen === 'function' ? originalOpen.apply(window, arguments) : null
        }
        // Markdown / 普通 anchor 的外链同样拦下。
        document.addEventListener('click', (event) => {
          const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
          if (anchor === null) return
          const href = anchor.href
          if (!self.open(href)) return
          event.preventDefault()
          event.stopPropagation()
        }, true)
      }

      installSidebar(ctx) {
        let sidebarRight
        try { sidebarRight = ctx.get('sidebarRight') } catch { sidebarRight = undefined }
        if (sidebarRight === undefined || typeof sidebarRight.openTab !== 'function') return
        if (sidebarRight.__dshIdeExternalTabOverride) return
        const original = sidebarRight.openTab
        const self = this
        try {
          sidebarRight.openTab = function (type, options) {
            const url = options && options.params && options.params.url
            if (type === 'browser' && self.open(url)) return
            return original.apply(sidebarRight, arguments)
          }
          sidebarRight.__dshIdeExternalTabOverride = true
        } catch (error) { console.warn(LOG, '无法覆盖 sidebarRight.openTab', error) }
      }
    }

    // =========================================================================================
    // DSH 设置页「打开配置文件」→ IDE 打开
    // =========================================================================================
    class SettingsDocumentBridge {
      constructor() { this.installed = false }

      install() {
        if (this.installed) return
        this.installed = true
        document.addEventListener('click', (event) => {
          const target = event.target instanceof Element
            ? event.target.closest('button, [role="button"], a')
            : null
          if (target === null) return
          const text = (target.textContent || '').replace(/\s+/g, ' ').trim()
          if (!SETTINGS_OPEN_TEXTS.has(text)) return
          const path = window.__dshIdeSettingsDocument
          const open = window.__dshIdeOpenFile
          if (typeof path !== 'string' || path === '' || typeof open !== 'function') return
          // 拦下 DSH 的原生打开（在 JCEF 里会走系统文件关联并失败），改由 IDE 打开。
          event.preventDefault()
          event.stopPropagation()
          try {
            open(JSON.stringify({ path, line: 0, column: 0 }))
            console.info(LOG, '设置配置文件已在 IDE 中打开：' + path)
          } catch (error) {
            console.warn(LOG, '在 IDE 中打开设置配置文件失败', error)
          }
        }, true)
      }
    }

    // =========================================================================================
    // 侧栏：隐藏工作区区块，只保留当前项目的会话（DOM 标记 + MutationObserver）
    // =========================================================================================
    class WorkspaceFilter {
      constructor() { this.currentWorkspaceId = undefined; this.started = false }
      ensureStyle() {
        if (document.getElementById(FILTER_STYLE_ID)) return
        const style = document.createElement('style')
        style.id = FILTER_STYLE_ID
        style.textContent = '[' + HIDE_ATTR + ']{display:none!important}'
        ;(document.head || document.documentElement).appendChild(style)
      }
      apply() {
        if (this.currentWorkspaceId === undefined) return
        document.querySelectorAll(GROUP_SELECTOR).forEach((section) => {
          const row = section.querySelector('[data-row-key^="' + ROW_KEY_PREFIX + '"]')
          const key = row ? (row.getAttribute('data-row-key') || '') : ''
          if (!key.startsWith(ROW_KEY_PREFIX)) return
          if (key.slice(ROW_KEY_PREFIX.length) === this.currentWorkspaceId) section.removeAttribute(HIDE_ATTR)
          else section.setAttribute(HIDE_ATTR, '')
        })
        this.hideWorkspaceChrome()
      }
      /**
       * IDE 里工作区由 `ideWorkspace` 固定，侧栏的「工作区」标题、搜索/视图/新建工作区按钮，
       * 以及唯一的项目行都是冗余信息，整块隐藏；会话分组本身保留。
       * header 与项目行不在同一父节点，先向上找到同时包含两者的容器再定位。
       * 侧栏收起成图标条时工作区行不渲染，但 rail 仍会渲染「添加工作区」和「搜索会话」
       * 两个 36px 控件，它们同样要隐藏（否则收起状态会多出这两个图标）。
       */
      hideWorkspaceChrome() {
        const rows = document.querySelectorAll('[data-row-key^="' + ROW_KEY_PREFIX + '"]')
        if (rows.length > 0) {
          rows.forEach((row) => row.setAttribute(HIDE_ATTR, ''))
          let container = rows[0].parentElement
          while (container !== null) {
            const header = container.querySelector('[class*="sectionHeader"]')
            if (header !== null) {
              header.setAttribute(HIDE_ATTR, '')
              break
            }
            container = container.parentElement
          }
        }
        document.querySelectorAll('[class*="_root"][class*="_rail"]').forEach((root) => {
          const add = root.querySelector('[class*="headerActions"] [class*="iconButton"]')
          if (add !== null) add.setAttribute(HIDE_ATTR, '')
          const search = root.querySelector('[class*="searchButton"]')
          if (search !== null) search.setAttribute(HIDE_ATTR, '')
        })
      }
      start(wsId) {
        this.currentWorkspaceId = wsId
        this.ensureStyle()
        if (this.started) { this.apply(); return }
        this.started = true
        let scheduled = false
        const run = () => { scheduled = false; try { this.apply() } catch { /* ignore */ } }
        const schedule = () => {
          if (scheduled) return
          scheduled = true
          if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(run)
          else setTimeout(run, 50)
        }
        schedule()
        new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true })
        console.info(LOG, '侧栏已隐藏工作区区块（含收起状态的新建工作区/搜索图标），仅显示当前项目的会话：' + wsId)
      }
    }

    // =========================================================================================
    // 文件资源 → IDE：覆盖 sidebarRight.openResource / openResourceIn
    // =========================================================================================
    class FileOpenBridge {
      constructor() { this.ctx = undefined; this.installed = false }

      /** `dsh-resource://file/session/<id>/<path>` 或 `.../absolute/<path>`。 */
      parseFileAddress(address) {
        if (address.indexOf(FILE_ADDRESS_PREFIX) !== 0) return undefined
        let rest = address.slice(FILE_ADDRESS_PREFIX.length)
        const cut = rest.search(/[?#]/)
        if (cut >= 0) rest = rest.slice(0, cut)
        const parts = rest.split('/')
        if (parts[0] === 'session') {
          if (parts.length < 3 || parts[1] === '') return undefined
          return { sessionId: decodeSegment(parts[1]), path: parts.slice(2).map(decodeSegment).join('/') }
        }
        if (parts[0] === 'absolute') {
          const unc = parts[1] === '' && parts.length > 2
          const segs = (unc ? parts.slice(2) : parts.slice(1)).map(decodeSegment)
          if (segs.length === 0 || segs[0] === '') return undefined
          if (unc) return { sessionId: undefined, path: '//' + segs.join('/') }
          if (/^[A-Za-z]:$/.test(segs[0])) return { sessionId: undefined, path: segs.join('/') }
          return { sessionId: undefined, path: '/' + segs.join('/') }
        }
        return undefined
      }

      /** `dsh-resource://changes-review/session/<id>/<seq>/<turn>`。 */
      parseReviewAddress(address) {
        if (address.indexOf(REVIEW_ADDRESS_PREFIX) !== 0) return undefined
        const parts = address.slice(REVIEW_ADDRESS_PREFIX.length).split('/')
        if (parts.length < 2 || parts[0] === '') return undefined
        const seq = parseInt(parts[1], 10)
        return isFinite(seq) ? { sessionId: decodeSegment(parts[0]), seq } : undefined
      }

      /** 相对路径按该会话 cwd 解析；拿不到 cwd 时原样交给 Kotlin（按项目根兜底）。 */
      resolveAddressPath(sessionId, path) {
        if (isAbsolutePath(path)) return path
        try {
          const sessions = this.ctx.get('sessions')
          if (sessions === undefined || sessionId === undefined) return path
          const summary = sessions.list.getSnapshot().byId[sessionId]
          const cwd = summary === undefined ? undefined : summary.cwd
          return typeof cwd === 'string' && cwd !== '' ? cwd.replace(/[\/]+$/, '') + '/' + path : path
        } catch { return path }
      }

      optionLine(o) { return o && o.params && typeof o.params.line === 'number' ? o.params.line : 0 }
      optionIndex(o) { return o && o.params && typeof o.params.index === 'number' ? o.params.index : 0 }

      /** 改动文件卡片取该轮摘要里第 index 个文件的路径。 */
      async changesFileAt(review, index) {
        try {
          const res = await fetch(
            CHANGES_SUMMARY_ROUTE + '?sessionId=' + encodeURIComponent(review.sessionId) + '&seq=' + encodeURIComponent(String(review.seq)),
            { credentials: 'same-origin' },
          )
          if (!res.ok) return undefined
          const summary = await res.json()
          const files = summary && typeof summary === 'object' && Array.isArray(summary.files) ? summary.files : []
          const file = files[index]
          if (!file || typeof file.path !== 'string' || file.path === '') return undefined
          return this.resolveAddressPath(review.sessionId, file.path)
        } catch { return undefined }
      }

      /** 文件地址直接交给 IDE；返回 true 表示已接管。 */
      openAddress(address, options, sessionId) {
        const bridge = window[OPEN_FILE_BRIDGE]
        if (typeof bridge !== 'function' || typeof address !== 'string') return false
        const file = this.parseFileAddress(address)
        if (file === undefined || file.path === '') return false
        try {
          bridge(JSON.stringify({
            path: this.resolveAddressPath(sessionId === undefined ? file.sessionId : sessionId, file.path),
            line: this.optionLine(options),
            column: 0,
          }))
          return true
        } catch (error) { console.warn(LOG, '调用 IDE 打开文件失败，回退内置预览', error); return false }
      }

      install(ctx) {
        this.ctx = ctx
        if (this.installed) return
        let sidebarRight
        try { sidebarRight = ctx.get('sidebarRight') } catch { sidebarRight = undefined }
        if (sidebarRight === undefined || typeof sidebarRight.openResource !== 'function') {
          console.warn(LOG, 'sidebarRight 不可用，文件点击仍走内置预览')
          return
        }
        const original = sidebarRight.openResource
        const originalIn = sidebarRight.openResourceIn
        try {
          sidebarRight.openResource = (address, options) => {
            if (this.openAddress(address, options, undefined)) return
            const bridge = window[OPEN_FILE_BRIDGE]
            if (typeof bridge === 'function' && typeof address === 'string') {
              const review = this.parseReviewAddress(address)
              if (review !== undefined) {
                void this.changesFileAt(review, this.optionIndex(options)).then((path) => {
                  if (path === undefined) { original.call(sidebarRight, address, options); return }
                  try { bridge(JSON.stringify({ path, line: 0, column: 0 })) } catch { original.call(sidebarRight, address, options) }
                }, () => original.call(sidebarRight, address, options))
                return
              }
            }
            return original.call(sidebarRight, address, options)
          }
          if (typeof originalIn === 'function') {
            sidebarRight.openResourceIn = (sessionId, address, options) => {
              if (this.openAddress(address, options, sessionId)) return
              return originalIn.call(sidebarRight, sessionId, address, options)
            }
          }
        } catch (error) { console.warn(LOG, '无法覆盖 sidebarRight.openResource', error); return }
        this.installed = true
        window.__dshIdeSidebarOpenOverride = true
        // 诊断入口：CDP / 控制台可显式触发一次资源打开。
        window.__dshIdeOpenResource = (address, options) => sidebarRight.openResource(address, options)
        window.__dshIdeOpenResourceIn = (sessionId, address, options) => sidebarRight.openResourceIn(sessionId, address, options)
        console.info(LOG, '已接管 sidebarRight.openResource/openResourceIn：文件资源在 IDE 中打开')
      }
    }

    // =========================================================================================
    // IDE → 对话：把文件 / 选区引用插入输入框
    // =========================================================================================
    class ComposerBridge {
      constructor() { this.ctx = undefined; this.installed = false }

      sessionCwdOf(sessionId) {
        try {
          const s = this.ctx.get('sessions').list.getSnapshot().byId[sessionId]
          return s === undefined ? undefined : s.cwd
        } catch { return undefined }
      }

      /** 把 IDE 的绝对路径变成页面里的引用描述；无效返回 undefined。 */
      refEntryOf(ref, cwd) {
        if (!ref || typeof ref.path !== 'string' || ref.path === '') return undefined
        const kind = ref.kind === 'directory' ? 'directory' : 'file'
        const relative = relativeRefPath(ref.path, cwd)
        if (relative === '') return undefined
        const mention = mentionOf(relative, kind)
        if (mention === undefined) return undefined
        return { mention, label: labelOf(relative, kind), appearance: kind === 'directory' ? 'folder' : 'file', suffix: lineSuffix(ref) }
      }

      /** ReferenceInsert 形态（官方 `@` 选择器同款）。 */
      toReferenceInsert(e) { return { source: 'reference', ref: e.mention, label: e.label, appearance: e.appearance, clipboardText: e.mention } }

      /** 插入点固定为草稿末尾；按 occurrences/claim 把 clipboard 长度折算回 detect 长度。 */
      endSpan(state) {
        let at = state.draft.length
        const occ = state.occurrences || []
        for (let i = 0; i < occ.length; i++) at -= Math.max(occ[i].length - 1, 0)
        if (state.claim !== undefined && state.claim !== null) at -= Math.max(state.claim.token.replace(/\s+$/u, '').length - 1, 0)
        at = Math.max(at, 0)
        return { start: at, end: at, draftRev: state.draftRev }
      }

      /** 末尾追加一个引用 chip；CAS 失败（用户同时在打字）返回 false。 */
      insertOneRef(input, entry) {
        let state = input.state.getSnapshot()
        if (state.draft.length > 0 && !/\s/u.test(state.draft.charAt(state.draft.length - 1))) {
          if (!input.actions.insertText(' ', this.endSpan(state))) return false
          state = input.state.getSnapshot()
        }
        const span = this.endSpan(state)
        const applied = typeof input.insertReference === 'function' && input.insertReference(this.toReferenceInsert(entry), span)
        if (applied) {
          if (entry.suffix !== '') input.actions.insertText(entry.suffix, this.endSpan(input.state.getSnapshot()))
          return true
        }
        return input.actions.insertText(entry.mention + ' ' + entry.suffix, this.endSpan(input.state.getSnapshot()))
      }

      /** 逐个追加；某个引用被 CAS 拒绝时停止，保留已插入的部分。 */
      insertRefs(input, entries) {
        let inserted = 0
        for (let k = 0; k < entries.length; k++) { if (!this.insertOneRef(input, entries[k])) break; inserted++ }
        return inserted
      }

      async insertRefsIntoComposer(refs) {
        const conversation = this.ctx.get('conversation')
        const sessions = this.ctx.get('sessions')
        if (conversation === undefined || conversation.input === undefined || sessions === undefined) {
          console.warn(LOG, 'conversation/sessions 不可用，忽略发送到对话框')
          return
        }
        const sessionId = persistedSessionId()
        if (sessionId === undefined) { console.warn(LOG, '没有当前会话，忽略发送到对话框'); return }
        const cwd = this.sessionCwdOf(sessionId) || readWorkspaceParam()
        const entries = refs.map((r) => this.refEntryOf(r, cwd)).filter((e) => e !== undefined)
        if (entries.length === 0) { console.warn(LOG, '没有可插入的文件引用', refs); return }
        const insert = (actx) => {
          const input = conversation.input.for(actx)
          const inserted = this.insertRefs(input, entries)
          if (inserted > 0) { try { input.focus() } catch { /* 聚焦只是体验优化 */ } }
          return inserted
        }
        const scope = sessions.scope(sessionId)
        if (scope !== undefined) { insert(scope); return }
        try {
          await sessions.using(sessionId, { source: 'workspaceOperation' }, (reference) => insert(reference.binding.ctx))
        } catch (error) { console.warn(LOG, '会话未就绪，无法插入引用：' + sessionId, error) }
      }

      install(ctx) {
        this.ctx = ctx
        window[INSERT_REFS_BRIDGE] = (json) => {
          let message
          try { message = JSON.parse(json) } catch (error) { console.warn(LOG, '无效的推送消息', json, error); return }
          if (message !== null && typeof message === 'object' && message.op === 'fileRefs' && Array.isArray(message.refs)) {
            void this.insertRefsIntoComposer(message.refs)
            return
          }
          console.warn(LOG, '未处理的推送类型', message === null ? message : message.op)
        }
        if (this.installed) return
        this.installed = true
        const pending = window.__dshIdePendingPushes
        if (Array.isArray(pending)) { window.__dshIdePendingPushes = []; pending.forEach((p) => window[INSERT_REFS_BRIDGE](p)) }
        // 诊断入口：CDP / 控制台可观察输入框 draft 与光标。
        window.__dshIdeComposerState = () => {
          try {
            const conversation = this.ctx.get('conversation')
            const sessions = this.ctx.get('sessions')
            const sessionId = persistedSessionId()
            const scope = sessionId === undefined ? undefined : sessions.scope(sessionId)
            if (conversation === undefined || scope === undefined) return 'conversation/scope unavailable'
            const input = conversation.input.for(scope)
            const state = input.state.getSnapshot()
            const span = input.actions.captureInsertion()
            return JSON.stringify({ draft: state.draft, rev: state.draftRev, phase: state.phase, span })
          } catch (error) { return 'debug failed: ' + (error && error.message) }
        }
        window.__dshIdeComposerReady = true
        console.info(LOG, '已接管「添加到 DSH 对话」：文件/选区引用插入输入框')
      }
    }

    // =========================================================================================
    // ideWorkspace 激活：登记当前 IDE 项目并切换
    // =========================================================================================
    class WorkspaceActivator {
      constructor(filter) { this.filter = filter }

      async activate(ctx, path) {
        const workspaces = ctx.get('workspaces')
        const uiWorkspace = ctx.get('uiWorkspace')
        if (workspaces === undefined || uiWorkspace === undefined) {
          console.warn(LOG, 'workspaces/uiWorkspace 服务不存在，跳过 ideWorkspace')
          return false
        }
        const ready = await waitUntil(() => workspaces.list.getSnapshot().phase === 'ready')
        if (!ready) { console.warn(LOG, '等待 Workspace 快照超时，保留 ideWorkspace 以便刷新重试'); return false }
        let view
        try { view = await workspaces.create({ path }) } catch (error) { console.warn(LOG, '登记 workspace 失败：' + path, error); return false }
        try { this.filter.start(view.workspaceId) } catch (error) { console.warn(LOG, '工作区过滤启动失败', error) }
        const snapshot = workspaces.list.getSnapshot()
        const sessions = ctx.get('sessions').list.getSnapshot()
        if (belongsToWorkspace(snapshot, sessions, view.workspaceId, persistedSessionId())) {
          console.info(LOG, '当前会话已在目标 workspace，沿用官方恢复：' + path)
          return true
        }
        try { await uiWorkspace.openWorkspace(view.workspaceId) } catch (error) { console.warn(LOG, '打开 workspace 失败：' + view.workspaceId, error); return false }
        console.info(LOG, '已切换到 IDE 项目：' + path)
        return true
      }
    }

    // =========================================================================================
    // 总装
    // =========================================================================================
    class IdeBridge {
      constructor() {
        this.filter = new WorkspaceFilter()
        this.fileBridge = new FileOpenBridge()
        this.composer = new ComposerBridge()
        this.activator = new WorkspaceActivator(this.filter)
      }
      apply(ctx) {
        try { this.composer.install(ctx) } catch (error) { console.warn(LOG, '发送到对话框桥安装失败', error) }
        try { this.fileBridge.install(ctx) } catch (error) { console.warn(LOG, '文件打开覆盖安装失败', error) }
        try { externalLinkBridge.installSidebar(ctx) } catch (error) { console.warn(LOG, '外部链接侧栏覆盖安装失败', error) }
        const idePath = readWorkspaceParam()
        if (idePath === undefined) return
        // IDE 内当前项目由 ideWorkspace 固定，隐藏「新建会话 Hero」里的项目/工作区选择器。
        try { this.hideHeroWorkspacePicker(ctx) } catch (error) { console.warn(LOG, '隐藏工作区选择器失败', error) }
        if (window[FLAG] !== idePath) {
          window[FLAG] = idePath
          void this.activator.activate(ctx, idePath).then((done) => {
            if (!done) window[FLAG] = undefined
          }).catch((error) => {
            console.warn(LOG, 'ideWorkspace 激活异常：' + idePath, error)
            window[FLAG] = undefined
          })
        }
      }

      /** 隐藏「新建会话 Hero」里的项目/工作区选择器（IDE 内当前项目由 ideWorkspace 固定）。 */
      hideHeroWorkspacePicker() {
        const id = 'dsh-ide-hide-workspace-picker'
        if (document.getElementById(id)) return
        const style = document.createElement('style')
        style.id = id
        // heroWorkspaceRow 是 CSS Modules 的局部名（前缀 hash 每版可变，用子串匹配更稳）。
        style.textContent = '[class*="heroWorkspaceRow"]{display:none!important}'
        ;(document.head || document.documentElement).appendChild(style)
      }
    }

    // 模块加载即执行：尽量早地把 Host 快照写回当前 origin 的 localStorage。
    const storageSnapshot = new StorageSnapshot()
    try { storageSnapshot.install() } catch (error) { console.warn(LOG, 'localStorage 快照初始化失败', error) }
    const settingsDocumentBridge = new SettingsDocumentBridge()
    try { settingsDocumentBridge.install() } catch (error) { console.warn(LOG, '设置配置文件桥初始化失败', error) }
    const externalLinkBridge = new ExternalLinkBridge()
    try { externalLinkBridge.installGlobal() } catch (error) { console.warn(LOG, '外部链接桥初始化失败', error) }

    const bridge = new IdeBridge()
    return {
      // IDE 桥需要这些服务。
      inject: ['workspaces', 'uiWorkspace', 'sessions', 'sidebarRight'],
      apply: (ctx) => bridge.apply(ctx),
    }
  },
})
