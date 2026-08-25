/** AI Copilot 抽屉：合并两类能力 —— ① 会话历史列表（可搜索/切换/删除）② 当前会话对话（含文件上下文快捷操作）。 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { appendSessionMessages, createSession, deleteSession, deleteMemory, getMessages, listMemories, listSessionItems, renameSession, sendMessageStream, summarizeFile } from '../api/agent'
import { getLLMConfig } from '../api/llmConfig'
import { buildConfigMap, getActiveProvider, PROVIDER_LABELS } from '../utils/settings'
import type { AgentSession, ChatMessage, FileItem, MemoryItem } from '../types'
import {
  IconChat, IconChevronRight, IconFile, IconFileText, IconPhoto, IconPlus, IconSearch, IconSend,
  IconSpark, IconTrash, IconVideo, IconWand,
} from './Icons'
import Markdown from './Markdown'

interface CopilotProps {
  open: boolean
  onClose: () => void
  selected: FileItem[]
  /** 外部（Dashboard）触发的总结请求：nonce 变化即执行一次，强制新建会话。 */
  summaryRequest?: { file: FileItem; nonce: number } | null
  /** 处理完 summaryRequest 后由父级清除该请求。 */
  onSummaryHandled?: () => void
}

const DEFAULT_WIDTH = 380
const MIN_WIDTH = 320
const MAX_WIDTH = 880

/** 按文件类型推断可用的快捷操作。 */
function quickActionsFor(file: FileItem | null): { key: string; label: string; icon: React.ReactNode; prompt: string }[] {
  const ext = file?.name.split('.').pop()?.toLowerCase() ?? ''
  const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext)
  const isVideo = ['mp4', 'mov', 'avi', 'mkv', 'webm', 'flv'].includes(ext)
  const isDoc = ['pdf', 'doc', 'docx', 'txt', 'md', 'xlsx', 'xls', 'csv', 'ppt', 'pptx'].includes(ext)
  const base = [
    { key: 'sum', label: '总结文档', icon: <IconFileText size={15} />, prompt: '帮我总结一下这个文件的核心内容，用要点列出。' },
    { key: 'rename', label: '智能重命名', icon: <IconWand size={15} />, prompt: '为这个文件建议一个更规范、表意更清晰的文件名。' },
  ]
  if (isImage) base.push({ key: 'enhance', label: '图像增强', icon: <IconPhoto size={15} />, prompt: '帮我分析这张图片，说明内容并给出增强建议。' })
  if (isVideo) base.push({ key: 'subtitle', label: '提取视频字幕', icon: <IconVideo size={15} />, prompt: '请尝试提取这个视频的字幕或要点。' })
  if (isDoc) base.push({ key: 'keywords', label: '提炼关键词', icon: <IconSpark size={15} />, prompt: '提炼这个文档的关键词和结论。' })
  return base.filter((a) => (isImage || isVideo || isDoc) ? true : a.key === 'sum' || a.key === 'rename')
}

/** 生成发给 Agent 的上下文前缀。 */
const contextFor = (files: FileItem[]) =>
  `（当前选中文件：${files.map((f) => `「${f.name}」`).join('、')}）`

/** 过滤可见消息。 */
const isUser = (m: ChatMessage) => m.role === 'user' || m.role === 'human'
const visible = (ms: ChatMessage[]) => ms.filter((m) => isUser(m) || m.role === 'ai')

export default function Copilot({ open, onClose, selected, summaryRequest, onSummaryHandled }: CopilotProps) {
  const [sessions, setSessions] = useState<AgentSession[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [search, setSearch] = useState('')
  const [showList, setShowList] = useState(false)
  const [showMemory, setShowMemory] = useState(false)
  const [memories, setMemories] = useState<MemoryItem[]>([])
  const [width, setWidth] = useState(DEFAULT_WIDTH)
  const [dragging, setDragging] = useState(false)
  const [aiBadge, setAiBadge] = useState('')
  // F1：流式回复独立 state——chunk 只追加 streaming，不每 chunk 复制整个消息列表（消除 O(n²)）
  const [streaming, setStreaming] = useState('')
  const streamingRef = useRef('')
  const streamingMetaRef = useRef<ChatMessage['usage'] | null>(null)
  const [streamingActive, setStreamingActive] = useState(false)
  // F2：SSE 中止 + 会话守卫
  const abortRef = useRef<AbortController | null>(null)
  const activeIdRef = useRef<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const list = visible(messages)

  /** 加载当前应用的供应商徽标（后端存储配置 + 本地 active 选择）。 */
  const loadAiBadge = useCallback(async () => {
    const map = buildConfigMap(await getLLMConfig().catch(() => []))
    const p = getActiveProvider()
    const cfg = map[p]
    if (cfg?.configured) setAiBadge(`${PROVIDER_LABELS[p] ?? p} / ${cfg.model}`)
    else setAiBadge(`${PROVIDER_LABELS[p] ?? p}（未配置）`)
  }, [])

  /** 拖拽调整抽屉宽度（左右）。 */
  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    setDragging(true)
    const startX = e.clientX
    const startW = width
    const onMove = (ev: MouseEvent) => {
      const w = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startW + (startX - ev.clientX)))
      setWidth(w)
    }
    const onUp = () => {
      setDragging(false)
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }

  const loadSessions = useCallback(async () => {
    try {
      const items = await listSessionItems()
      setSessions(items)
    } catch {
      setSessions([])
    }
  }, [])

  /** 中止进行中的流式请求（切换/关闭/新建会话时调用）。 */
  const abortStream = useCallback(() => { abortRef.current?.abort() }, [])

  /** 打开抽屉时加载会话列表与 AI 状态；关闭时复位并中止在途流。 */
  useEffect(() => {
    if (open) { loadSessions(); loadAiBadge() }
    else {
      abortStream()
      setShowList(false); setShowMemory(false); setActiveId(null); setMessages([])
    }
  }, [open, loadSessions, loadAiBadge, abortStream])

  /** 加载长期记忆列表。 */
  const loadMemories = useCallback(async () => {
    try {
      const res = await listMemories()
      setMemories(res.data ?? [])
    } catch {
      setMemories([])
    }
  }, [])

  /** 切换记忆面板时按需加载。 */
  useEffect(() => {
    if (open && showMemory) loadMemories()
  }, [open, showMemory, loadMemories])

  /** 同步当前会话 id（流式守卫用）。 */
  useEffect(() => { activeIdRef.current = activeId }, [activeId])

  /** F1：近底才吸附滚动（瞬时，尊重用户上滚）。 */
  useEffect(() => {
    const el = scrollRef.current
    if (!el) return
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 80) {
      el.scrollTop = el.scrollHeight
    }
  }, [messages, streaming, busy])

  // 卸载时中止在途流
  useEffect(() => () => abortStream(), [abortStream])

  /** 新建会话：回到空对话草稿。 */
  const handleNew = () => {
    abortStream()
    setActiveId(null)
    setMessages([])
    setShowList(false)
  }

  /** 打开某个历史会话：显式加载消息（不依赖 activeId effect，避免发送时被覆盖）。 */
  const openSession = (id: string) => {
    abortStream()
    setActiveId(id)
    setShowList(false)
    getMessages(id).then((res) => {
      if (res.data) setMessages(res.data)
    })
  }

  /** 删除会话。 */
  const handleDelete = async (id: string) => {
    await deleteSession(id)
    if (id === activeId) { setActiveId(null); setMessages([]) }
    await loadSessions()
  }

  /** 删除记忆。 */
  const handleDeleteMemory = async (fact: string) => {
    try {
      await deleteMemory(fact.slice(0, 12))
      await loadMemories()
    } catch {
      /* 忽略删除失败 */
    }
  }

  /** 记忆相对时间。 */
  const memoryTime = (ts: number) => {
    const d = new Date(ts * 1000)
    const days = Math.floor((Date.now() - ts * 1000) / 86400000)
    if (days <= 0) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    if (days === 1) return '昨天'
    if (days < 30) return `${days} 天前`
    return d.toLocaleDateString('zh-CN')
  }

  const run = async (text: string) => {
    const useText = text.trim()
    if (!useText || busy) return
    setInput('')
    // 未配置当前供应商 AI 参数（Base URL / API Key / 模型 三要素缺一）时拦截，不发请求。
    // 配置由后端加密存储，此处实时拉取判断（含 Tavily 时不作为聊天门槛）。
    const cfgMap = buildConfigMap(await getLLMConfig().catch(() => []))
    const pc = cfgMap[getActiveProvider()]
    if (!pc || !pc.configured) {
      setMessages((m) => [
        ...m,
        { role: 'user', content: useText },
        { role: 'ai', content: '（请先在 设置 → AI 配置 中配置 Base URL、API Key 与模型后再聊天）' },
      ])
      return
    }
    setMessages((m) => [...m, { role: 'user', content: useText }])
    setBusy(true)
    // F1：流式文本走独立 state（chunk 单串追加，不复制整个消息列表）
    streamingRef.current = ''
    setStreaming('')
    streamingMetaRef.current = null
    setStreamingActive(true)
    // F2：中止上一次在途流，建立本次 AbortController
    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac
    let isNew = false
    let sid: string | null = null
    try {
      sid = activeId
      if (!sid) {
        const res = await createSession('新对话')
        if (!res.data) throw new Error('会话创建失败')
        sid = res.data.id
        isNew = true
        activeIdRef.current = sid
        setActiveId(sid)
        await loadSessions()
      } else {
        activeIdRef.current = sid
      }
      for await (const ev of sendMessageStream(sid, useText, ac.signal)) {
        // F2：切换会话/关闭抽屉后立即中止，不再污染当前视图
        if (activeIdRef.current !== sid) { ac.abort(); break }
        if (ev.type === 'text' && ev.content) {
          streamingRef.current += ev.content
          setStreaming(streamingRef.current)
        } else if (ev.type === 'meta') {
          const meta = ev
          streamingMetaRef.current = {
            input_tokens: meta.input_tokens ?? 0,
            output_tokens: meta.output_tokens ?? 0,
            total_tokens: meta.total_tokens ?? 0,
            model: meta.model,
            provider: meta.provider,
            context_window: meta.context_window,
            cost_yuan: meta.cost_yuan,
            latency_ms: meta.latency_ms,
            truncated: meta.truncated,
            summary_used: meta.summary_used,
            dropped_messages: meta.dropped_messages,
            summary_text: meta.summary_text,
            prompt_cache_hit_tokens: meta.prompt_cache_hit_tokens ?? 0,
            prompt_cache_miss_tokens: meta.prompt_cache_miss_tokens ?? 0,
          }
        } else if (ev.type === 'error') {
          streamingRef.current = ev.content ? `（${ev.content}）` : '（处理失败，请重试）'
          setStreaming(streamingRef.current)
        }
      }
      // 流结束：一次性把流式回复并入消息列表（带 usage），中止/切换则丢弃
      if (!ac.signal.aborted && activeIdRef.current === sid) {
        setMessages((m) => [
          ...m,
          { role: 'ai', content: streamingRef.current, usage: streamingMetaRef.current ?? undefined },
        ])
      }
      /** 首次对话自动以第一句话命名会话。 */
      if (isNew) {
        await renameSession(sid, useText.slice(0, 20))
      }
      await loadSessions()
    } catch {
      // 中止（切换/关闭）不写错误文案
      if (!ac.signal.aborted && activeIdRef.current === sid) {
        setMessages((m) => [...m, { role: 'ai', content: '（发送失败，请重试）' }])
      }
    } finally {
      streamingRef.current = ''
      setStreaming('')
      streamingMetaRef.current = null
      setStreamingActive(false)
      if (abortRef.current === ac) abortRef.current = null
      setBusy(false)
    }
  }

  /** 快捷「总结文档」：直连专用摘要端点（单次 LLM 调用），并写入当前会话（无则新建）。 */
  const runSummary = async (file: FileItem, fresh = false) => {
    if (!file || busy) return
    const cfgMap = buildConfigMap(await getLLMConfig().catch(() => []))
    const pc = cfgMap[getActiveProvider()]
    const userText = `帮我总结一下「${file.name}」的核心内容`
    if (!pc || !pc.configured) {
      setMessages((m) => [
        ...m,
        { role: 'user', content: userText },
        { role: 'ai', content: '（请先在 设置 → AI 配置 中配置 Base URL、API Key 与模型后再总结）' },
      ])
      return
    }
    setMessages((m) => [...m, { role: 'user', content: userText }, { role: 'ai', content: '' }])
    setBusy(true)
    let sid = activeId
    try {
      if (fresh || !sid) {
        const res = await createSession(`总结·${file.name}`)
        if (!res.data) throw new Error('会话创建失败')
        sid = res.data.id
        setActiveId(sid)
        await loadSessions()
      }
      const res = await summarizeFile(file.id, file.name)
      const summary = res?.summary || '（摘要生成失败，请重试）'
      await appendSessionMessages(sid, [
        { role: 'user', content: userText },
        { role: 'ai', content: summary },
      ])
      setMessages((m) => {
        const n = [...m]
        n[n.length - 1] = { role: 'ai', content: summary }
        return n
      })
      await loadSessions()
    } catch {
      setMessages((m) => {
        const n = [...m]
        n[n.length - 1] = { role: 'ai', content: '（总结失败，请重试）' }
        return n
      })
    } finally {
      setBusy(false)
    }
  }

  /** 响应外部（Dashboard）总结请求：总是新建会话执行。 */
  useEffect(() => {
    if (open && summaryRequest) {
      setActiveId(null)
      setMessages([])
      runSummary(summaryRequest.file, true)
      onSummaryHandled?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, summaryRequest?.nonce])

  const sendQuick = (a: { key: string; prompt: string }) => {
    if (a.key === 'sum') {
      runSummary(selected[0])
      return
    }
    run(`${contextFor(selected)} ${a.prompt}`)
  }
  const filtered = sessions.filter((s) =>
    (s.title || '').toLowerCase().includes(search.trim().toLowerCase()) ||
    (s.last_preview || '').toLowerCase().includes(search.trim().toLowerCase()),
  )

  return (
    <aside
      className={`relative shrink-0 flex flex-col bg-white border-l border-line overflow-hidden ${
        open ? '' : 'w-0'
      } ${dragging ? '' : 'transition-[width] duration-300'}`}
      style={{ width: open ? width : undefined }}
    >
      {/* 拖拽手柄（左侧边缘） */}
      {open && (
        <div
          onMouseDown={startResize}
          title="拖拽调整宽度"
          className="absolute left-0 top-0 bottom-0 w-1.5 cursor-col-resize z-20 bg-transparent hover:bg-brand/20 active:bg-brand/30 transition-colors"
        />
      )}
      {/* 面板头 */}
      <div className="flex items-center gap-2 px-4 h-14 border-b border-line">
        <span className="chip-brand shrink-0"><IconSpark size={13} /> AI Copilot</span>
        {aiBadge && (
          <span className="min-w-0 truncate text-[11px] text-ink-mute" title={aiBadge}>
            {aiBadge}
          </span>
        )}
        <div className="flex-1" />
        <button
          onClick={() => setShowList((v) => !v)}
          className={`icon-btn ${showList ? 'bg-canvas text-brand-deep' : ''}`}
          title="会话列表"
        >
          <IconChat size={17} />
        </button>
        <button
          onClick={() => setShowMemory((v) => !v)}
          className={`icon-btn ${showMemory ? 'bg-canvas text-brand-deep' : ''}`}
          title="长期记忆"
        >
          <IconSpark size={17} />
        </button>
        <button onClick={handleNew} className="icon-btn" title="新对话">
          <IconPlus size={17} />
        </button>
        <button onClick={onClose} className="icon-btn" title="收起面板">
          <IconChevronRight size={17} />
        </button>
      </div>

      {open && showList && (
        <div className="flex-1 flex flex-col min-h-0">
          {/* 搜索框 */}
          <div className="p-3 pb-2">
            <div className="panel flex items-center gap-1 p-1.5">
              <IconSearch size={16} className="text-ink-mute ml-2 shrink-0" />
              <input
                className="w-full bg-transparent px-2 py-1.5 text-sm focus:outline-none placeholder:text-ink-mute"
                placeholder="搜索会话…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
          </div>
          {/* 会话列表 */}
          <div className="flex-1 overflow-y-auto px-2 pb-3 space-y-1">
            {filtered.map((s) => (
              <div
                key={s.id}
                className={`group flex items-center gap-2.5 px-3 py-2.5 rounded-xl cursor-pointer transition-colors ${
                  s.id === activeId ? 'bg-brand-soft text-brand-deep' : 'hover:bg-canvas text-ink'
                }`}
                onClick={() => openSession(s.id)}
              >
                <IconChat size={16} className="text-ink-mute shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className={`text-sm truncate ${s.id === activeId ? 'font-semibold' : 'font-medium'}`}>{s.title || '（未命名）'}</p>
                  <p className="text-xs text-ink-mute truncate mt-0.5">
                    {s.last_preview || '（空）'}
                    {typeof s.cost_yuan === 'number' && s.cost_yuan > 0 && (
                      <span className="ml-1.5 font-mono text-[10px] text-brand-deep/70">¥{s.cost_yuan.toFixed(4)}</span>
                    )}
                  </p>
                </div>
                <button
                  onClick={(e) => { e.stopPropagation(); handleDelete(s.id) }}
                  className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                  title="删除会话"
                >
                  <IconTrash size={15} className="text-danger" />
                </button>
              </div>
            ))}
            {filtered.length === 0 && (
              <p className="text-center text-sm text-ink-mute py-10 px-4 leading-relaxed">
                {search.trim() ? '没有匹配的会话' : '暂无历史对话\n点击右上角「+」新开对话'}
              </p>
            )}
          </div>
        </div>
      )}

      {open && showMemory && (
        <div className="flex-1 flex flex-col min-h-0">
          <div className="px-4 py-3 flex items-center gap-2">
            <span className="text-xs text-ink-mute">对话中自动记录的长期偏好与事实</span>
            <button
              onClick={loadMemories}
              className="ml-auto text-xs text-brand-deep hover:underline shrink-0"
            >
              刷新
            </button>
          </div>
          <div className="flex-1 overflow-y-auto px-4 pb-4 space-y-1.5">
            {memories.length === 0 ? (
              <p className="text-center text-sm text-ink-mute py-10 px-4 leading-relaxed">
                暂无长期记忆\n对话中谈到偏好、身份或习惯时我会自动记下来
              </p>
            ) : (
              memories.map((m, i) => (
                <div key={i} className="group flex items-start gap-2.5 px-3 py-2.5 rounded-xl border border-line bg-canvas/50">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm text-ink leading-relaxed">{m.fact}</p>
                    <p className="text-[10px] text-ink-mute mt-1">{memoryTime(m.created_at)}</p>
                  </div>
                  <button
                    onClick={() => handleDeleteMemory(m.fact)}
                    className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
                    title="删除该记忆"
                  >
                    <IconTrash size={14} className="text-danger" />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {open && !showList && !showMemory && (
        <div className="flex-1 flex flex-col min-h-0">
          {/* 当前会话信息 */}
          {activeId && (
            <div className="px-4 pt-3 flex items-center gap-2">
              <span className="text-xs text-ink-mute truncate">
                {sessions.find((s) => s.id === activeId)?.title || '当前会话'}
              </span>
              <button
                onClick={() => setShowList(true)}
                className="ml-auto text-xs text-brand-deep hover:underline shrink-0"
              >
                切换会话
              </button>
            </div>
          )}

          {/* 选中上下文 */}
          {selected.length > 0 && (
            <div className={`px-4 pt-4 ${messages.length ? 'pb-2' : 'pb-4'}`}>
              <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-mute mb-2">文件上下文</p>
              <div className="flex flex-wrap gap-1.5">
                {selected.map((f) => {
                  const ext = f.name.split('.').pop()?.toLowerCase() ?? ''
                  const isImg = ['png', 'jpg', 'jpeg', 'webp'].includes(ext)
                  const isVid = ['mp4', 'mov', 'mkv', 'webm', 'flv'].includes(ext)
                  return (
                    <span key={f.id} className="chip-soft max-w-full">
                      {isImg ? <IconPhoto size={12} /> : isVid ? <IconVideo size={12} /> : <IconFile size={12} />}
                      <span className="truncate">{f.name}</span>
                    </span>
                  )
                })}
              </div>
            </div>
          )}

          {/* 快捷 Prompt 区 */}
          {selected.length > 0 && (
            <div className="px-4 pb-4">
              <p className="text-[11px] font-semibold uppercase tracking-wider text-ink-mute mb-2">快捷操作</p>
              <div className="grid grid-cols-2 gap-2">
                {quickActionsFor(selected[0]).map((a) => (
                  <button
                    key={a.key}
                    onClick={() => sendQuick(a)}
                    disabled={busy}
                    className="flex items-center gap-2 px-3 py-2.5 rounded-xl border border-line bg-canvas/50 hover:bg-brand-soft hover:border-brand/40 hover:text-brand-deep text-sm text-ink-soft disabled:opacity-40 disabled:pointer-events-none transition-all active:scale-[.98]"
                  >
                    {a.icon}
                    <span className="truncate">{a.label}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 消息区 */}
          <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto px-4 pb-4">
            {messages.length === 0 ? (
              <div className="h-full flex items-center justify-center text-center px-6">
                <div>
                  <div className={`mx-auto w-12 h-12 rounded-2xl bg-brand-soft text-brand-deep inline-flex items-center justify-center ${selected.length ? '' : 'animate-floaty'}`}>
                    <IconSpark size={22} />
                  </div>
                  <p className="text-sm text-ink-soft mt-3">
                    {selected.length
                      ? '点击上方快捷操作，或直接提问'
                      : '在左侧选中一个或多个文件，\n我会给出针对性的操作建议\n\n也可以点右上角「新对话」开始自由对话'}
                  </p>
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                {list.map((m, i) => (
                  <div key={i} className={`flex ${isUser(m) ? 'justify-end' : 'justify-start'}`}>
                    <div
                      className={`max-w-[88%] px-3.5 py-2.5 rounded-2xl text-sm ${
                        isUser(m)
                          ? 'bg-gradient-to-br from-brand to-brand-deep text-white rounded-br-sm whitespace-pre-wrap'
                          : 'bg-canvas border border-line rounded-bl-sm text-ink'
                      }`}
                    >
                      {isUser(m) ? (
                        m.content
                      ) : m.content ? (
                        <Markdown>{m.content}</Markdown>
                      ) : (
                        i === list.length - 1 && busy && (
                          <span className="inline-flex gap-1 py-1">
                            {[0, 1, 2].map((d) => (
                              <span key={d} className="w-1.5 h-1.5 bg-ink-mute rounded-full"
                                style={{ animation: `pulseDot 1.2s ease-in-out ${d * 0.18}s infinite` }} />
                            ))}
                          </span>
                        )
                      )}
                      {/* AI 消耗信息行：模型 · 上下文 · ↑↓tokens · 花费 · 耗时 */}
                      {!isUser(m) && m.usage && (
                        <div className="flex flex-wrap gap-x-2 gap-y-0.5 mt-1.5 text-[10px] text-ink-mute font-mono">
                          {m.usage.model && <span className="text-brand-deep font-sans">{m.usage.model}</span>}
                          {typeof m.usage.context_window === 'number' && (
                            <span>{m.usage.context_window >= 1e6 ? `${(m.usage.context_window / 1e6).toFixed(1)}M` : `${(m.usage.context_window / 1000).toFixed(0)}K`} ctx</span>
                          )}
                          <span>↑{m.usage.input_tokens}</span>
                          <span>↓{m.usage.output_tokens}</span>
                          {typeof m.usage.prompt_cache_hit_tokens === 'number' && m.usage.prompt_cache_hit_tokens > 0 && (
                            <span className="text-brand-deep">☑ 缓存 {m.usage.prompt_cache_hit_tokens}</span>
                          )}
                          {typeof m.usage.cost_yuan === 'number' && m.usage.cost_yuan > 0 && (
                            <span>¥{m.usage.cost_yuan.toFixed(4)}</span>
                          )}
                          {typeof m.usage.latency_ms === 'number' && <span>{m.usage.latency_ms}ms</span>}
                        </div>
                      )}
                      {!isUser(m) && m.usage?.truncated && (
                        <p className="mt-1 text-[10px] text-amber-600">
                          {m.usage.summary_used
                            ? `历史过长，已摘要 ${m.usage.dropped_messages ?? 0} 条早期对话`
                            : `历史过长，已省略 ${m.usage.dropped_messages ?? 0} 条早期对话`}
                        </p>
                      )}
                      {!isUser(m) && m.usage?.summary_text && (
                        <details className="mt-1 rounded-lg border border-line bg-canvas/50 px-2 py-1.5 text-[11px] text-ink-mute">
                          <summary className="cursor-pointer select-none text-amber-600/80">
                            查看早期对话摘要
                          </summary>
                          <p className="mt-1 whitespace-pre-wrap leading-relaxed">{m.usage.summary_text}</p>
                        </details>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
            {/* F1：流式气泡——chunk 只更新此块，不重渲染整个消息列表 */}
            {streamingActive && (
              <div className="flex justify-start">
                <div className="max-w-[88%] px-3.5 py-2.5 rounded-2xl text-sm bg-canvas border border-line rounded-bl-sm text-ink">
                  {streaming ? (
                    <Markdown>{streaming}</Markdown>
                  ) : (
                    <span className="inline-flex gap-1 py-1">
                      {[0, 1, 2].map((d) => (
                        <span key={d} className="w-1.5 h-1.5 bg-ink-mute rounded-full"
                          style={{ animation: `pulseDot 1.2s ease-in-out ${d * 0.18}s infinite` }} />
                      ))}
                    </span>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* 输入区 */}
          <div className="shrink-0 border-t border-line p-3">
            <div className="flex items-end gap-2 border border-line rounded-xl bg-canvas/60 focus-within:ring-2 focus-within:ring-brand/20 px-2 py-1">
              <textarea
                rows={1}
                className="flex-1 resize-none bg-transparent px-2 py-2 text-sm focus:outline-none placeholder:text-ink-mute"
                placeholder="提问选中文件…"
                disabled={busy}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    run(input)
                  }
                }}
              />
              <button
                onClick={() => run(input)}
                disabled={busy || !input.trim()}
                className="btn-primary !p-2"
                title="发送"
              >
                <IconSend size={15} />
              </button>
            </div>
            <p className="text-[11px] text-ink-mute mt-1.5 text-center">
              {selected.length ? `作用于 ${selected.length} 个文件 · Enter 发送` : 'Enter 发送 · 自由对话'}
            </p>
          </div>
        </div>
      )}
    </aside>
  )
}
