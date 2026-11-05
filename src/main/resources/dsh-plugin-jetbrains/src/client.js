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
    // 侧栏只显示当前项目（DOM 标记 + MutationObserver）
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
        console.info(LOG, '工作区列表仅显示当前项目：' + wsId)
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

    const bridge = new IdeBridge()
    return {
      // IDE 桥需要这些服务。
      inject: ['workspaces', 'uiWorkspace', 'sessions', 'sidebarRight'],
      apply: (ctx) => bridge.apply(ctx),
    }
  },
})
