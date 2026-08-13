/** 仪表盘页面：三栏 SaaS 布局（左侧导航 / 中央主区 / 右侧 AI Copilot 面板）。
 *  中央主区支持「全部文件」「任务列表」两个视图。 */
import { useState, useEffect, useCallback, useRef } from 'react'
import {
  listFiles, uploadFileWithProgress, deleteFile, getDownloadUrl, renameFile, moveFile,
  checksumFile, initMultipart, uploadPart, completeMultipart, abortMultipart, searchFiles,
} from '../api/files'
import { indexFile, indexFolder, getIndexStatus, unindexFile, getFolderIndexStatus, unindexFolder } from '../api/agent'
import { getRootFolders, createFolder, deleteFolder, renameFolder, moveFolder } from '../api/folders'
import { getProfile } from '../api/auth'
import { createShare, deleteShare } from '../api/shares'
import { computeFileMD5 } from '../utils/md5'
import type { FileItem, Folder, User } from '../types'
import Sidebar, { type SidebarSection } from '../components/Sidebar'
import Copilot from '../components/Copilot'
import FileTree from '../components/FileTree'
import TaskList, { type UploadTaskItem } from '../components/TaskList'
import ActionDialog from '../components/ActionDialog'
import ConfirmDialog from '../components/ConfirmDialog'
import QuotaBadge from '../components/QuotaBadge'
import SearchResults from '../components/SearchResults'
import PreviewModal from '../components/PreviewModal'
import ShareDialog, { type ShareState } from '../components/ShareDialog'
import {
  IconFolder, IconInbox, IconPlus, IconSearch, IconSpark, IconUpload,
} from '../components/Icons'

export default function Dashboard() {
  const [files, setFiles] = useState<FileItem[]>([])
  const [folders, setFolders] = useState<Folder[]>([])
  const [newFolderName, setNewFolderName] = useState('')
  const [profile, setProfile] = useState<User | null>(null)

  // 布局状态
  const [section, setSection] = useState<SidebarSection>('files')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [copilotOpen, setCopilotOpen] = useState(true)
  // 文件多选
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  // 上传任务队列
  const [tasks, setTasks] = useState<UploadTaskItem[]>([])
  // 树刷新令牌：每次 load 后 +1，强制已展开子树重新拉取
  const [treeEpoch, setTreeEpoch] = useState(0)
  // 搜索：顶栏输入（searchInput）与已提交查询（query）
  const [searchInput, setSearchInput] = useState('')
  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<FileItem[]>([])
  const [searchLoading, setSearchLoading] = useState(false)
  // 预览
  const [preview, setPreview] = useState<FileItem | null>(null)
  // 分享弹窗
  const [share, setShare] = useState<ShareState | null>(null)
  // AI 总结请求：交给右侧 Copilot 抽屉新建会话执行（nonce 变化即触发）
  const [summaryRequest, setSummaryRequest] = useState<{ file: FileItem; nonce: number } | null>(null)
  // 操作对话框状态：当前待操作对象
  const [dialog, setDialog] = useState<null | {
    mode: 'rename-file' | 'rename-folder' | 'move-file' | 'move-folder'
    id: number
    name: string
  }>(null)
  // 删除确认状态
  const [confirm, setConfirm] = useState<null | {
    title: string
    message: string
    onConfirm: () => Promise<void>
  }>(null)
  // 轻量 toast 提示
  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<number>()

  const showToast = useCallback((msg: string) => {
    setToast(msg)
    if (toastTimer.current) window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 3500)
  }, [])

  // 文件索引状态缓存（indexedMap[id] === true 表示已建立索引）
  const [indexedMap, setIndexedMap] = useState<Record<number, boolean>>({})
  const indexedQueried = useRef<Set<number>>(new Set())

  /** FileTree 上报可见文件 id，批量拉取未查询过的索引状态。 */
  const handleFilesVisible = useCallback(async (ids: number[]) => {
    const fresh = ids.filter((id) => !indexedQueried.current.has(id))
    if (fresh.length === 0) return
    fresh.forEach((id) => indexedQueried.current.add(id))
    try {
      const res = await getIndexStatus(fresh)
      const map = res ?? {}
      setIndexedMap((prev) => {
        const next = { ...prev }
        for (const k of Object.keys(map)) {
          const id = Number(k)
          if (Number.isInteger(id)) next[id] = !!map[k]
        }
        return next
      })
    } catch {
      /* 状态拉取失败保持未知（按未索引处理） */
    }
  }, [])

  const updateIndexed = useCallback((id: number, value: boolean) => {
    setIndexedMap((prev) => ({ ...prev, [id]: value }))
  }, [])

  const selectedFiles = files.filter((f) => selectedIds.has(f.id))
  const activeUploads = tasks.filter((t) => t.status === 'queued' || t.status === 'hashing' || t.status === 'uploading').length

  /** 刷新文件列表、文件夹列表与用户配额。 */
  const load = useCallback(async () => {
    try {
      const [fileList, folderList] = await Promise.all([listFiles(), getRootFolders()])
      setFiles(fileList)
      if (folderList.data) setFolders(folderList.data)
    } catch {
      /* 加载失败时保持空列表 */
    }
    getProfile().then((u) => setProfile(u ?? null)).catch(() => {})
    // 列表已刷新：清空已查询标记，让可见文件重新拉取索引状态（删除/编辑后状态随之更新）
    indexedQueried.current.clear()
    setTreeEpoch((e) => e + 1)
  }, [])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    setSelectedIds(new Set())
    setQuery('')
    setSearchInput('')
    setSearchResults([])
  }, [section])

  const setTask = (id: string, patch: Partial<UploadTaskItem>) => {
    setTasks((prev) => prev.map((t) => (t.id === id ? { ...t, ...patch } : t)))
  }

  const MULTIPART_THRESHOLD = 50 * 1024 * 1024 // >50MB 走分块
  const CHUNK_SIZE = 5 * 1024 * 1024            // 5MB/块
  const MAX_CONCURRENCY = 5                     // 并发 ≤5

  /** 分块上传：并发≤5，支持跨网络中断续传（复用 upload_id 跳过已传块）。 */
  const uploadMultipart = async (file: File, folderId: number | undefined, md5: string, onSetProgress: (p: number) => void) => {
    const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
    const init = await initMultipart({
      name: file.name, size: file.size, mime_type: file.type || 'application/octet-stream',
      folder_id: folderId, md5, chunk_size: CHUNK_SIZE,
    })
    if (!init) throw new Error('分块初始化失败')
    const { upload_id } = init

    // 续传：先尝试 complete，若报"分块不完整"则补传缺失块（跨网络中断恢复场景）
    try {
      const done = await completeMultipart(upload_id)
      if (done) { onSetProgress(100); return done }
    } catch { /* 分块未齐，继续补传 */ }

    let done = false
    let attempt = 0
    while (!done && attempt < 3) {
      attempt += 1
      // 并发上传全部块；失败块重试
      const failIndices: number[] = []
      let uploadedBytes = 0
      const worker = async (idx: number) => {
        const start = idx * CHUNK_SIZE
        const end = Math.min(start + CHUNK_SIZE, file.size)
        const blob = file.slice(start, end)
        try {
          await uploadPart(upload_id, idx, blob)
          uploadedBytes += blob.size
          onSetProgress(Math.round((uploadedBytes / file.size) * 100))
        } catch {
          failIndices.push(idx)
        }
      }
      const queue = Array.from({ length: totalChunks }, (_, i) => i)
      // 简单并发池
      let cursor = 0
      const workers: Promise<void>[] = []
      while (cursor < Math.min(MAX_CONCURRENCY, queue.length)) {
        workers.push((async () => {
          while (true) {
            const idx = queue[cursor++]
            if (idx === undefined) return
            await worker(idx)
          }
        })())
      }
      await Promise.all(workers)
      if (failIndices.length > 0 && attempt >= 3) throw new Error(`仍有 ${failIndices.length} 块失败`)
      if (failIndices.length > 0) { continue }
      const result = await completeMultipart(upload_id)
      if (result) { onSetProgress(100); return result }
      throw new Error('合并失败')
    }
    throw new Error('分块上传失败')
  }

  /** 上传一个文件：MD5 → 秒传预检 → 命中秒传 / >50MB 分块 / 否则直传。 */
  const uploadOne = async (file: File, folderId?: number) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    setTasks((prev) => [...prev, { id, name: file.name, size: file.size, progress: 0, status: 'queued' }])
    try {
      // 1. 计算 MD5（前端增量，显示"校验中"）
      setTask(id, { status: 'hashing', progress: 5 })
      const md5 = await computeFileMD5(file, (p) => {
        setTask(id, { progress: Math.round(5 + p * 10) })
      })
      // 2. 秒传预检
      const chk = await checksumFile({ md5, name: file.name, size: file.size, folder_id: folderId })
      if (chk?.instant) {
        setTask(id, { status: 'instant', progress: 100 })
        await load()
        return
      }
      // 3. 分流
      if (file.size > MULTIPART_THRESHOLD) {
        setTask(id, { status: 'uploading', progress: 15 })
        await uploadMultipart(file, folderId, md5, (p) => setTask(id, { progress: Math.max(15, p) }))
      } else {
        setTask(id, { status: 'uploading', progress: 15 })
        await uploadFileWithProgress(file, folderId, (percent) => {
          setTask(id, { progress: Math.max(15, percent) })
        })
      }
      setTask(id, { status: 'done', progress: 100 })
      await load()
    } catch {
      setTask(id, { status: 'error' })
    }
  }

  /** 处理文件上传（多文件队列，全并行）。 */
  const handleUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const filesArr = e.target.files ? Array.from(e.target.files) : []
    if (filesArr.length === 0) return
    filesArr.forEach((f) => {
      // 超过 100MB 无法建立索引，但后端支持分块（上限 10GB），仍允许上传并提示
      if (f.size > 100 * 1024 * 1024) showToast(`「${f.name}」超过 100MB，无法建立索引（仍可上传）`)
      uploadOne(f)
    })
    // 允许重复选择同名文件
    e.target.value = ''
  }

  /** 创建文件夹。 */
  const handleCreateFolder = async () => {
    if (!newFolderName.trim()) return
    await createFolder(newFolderName.trim())
    setNewFolderName('')
    await load()
  }

  /** 删除文件。 */
  const handleDeleteFiles = async () => {
    for (const id of selectedIds) await deleteFile(id)
    setSelectedIds(new Set())
    await load()
  }

  /** 删除文件（单个图标）。 */
  const handleDeleteFile = async (id: number) => {
    await deleteFile(id)
    await load()
  }

  /** 删除文件夹。 */
  const handleDeleteFolder = async (id: number) => {
    await deleteFolder(id)
    await load()
  }

  /** 重命名（文件或文件夹）。 */
  const handleRename = async (name: string) => {
    if (!dialog) return
    const { mode, id } = dialog
    if (mode === 'rename-file') await renameFile(id, name)
    else await renameFolder(id, name)
    setDialog(null)
    await load()
  }

  /** 移动（文件或文件夹），target===0 表示移到根目录。 */
  const handleMove = async (target: number | null) => {
    if (!dialog) return
    const { mode, id } = dialog
    const targetId = target ?? 0
    if (mode === 'move-file') await moveFile(id, targetId)
    else await moveFolder(id, targetId)
    setDialog(null)
    await load()
  }

  /** 拖拽移动：文件到目标文件夹（null 表示根目录）。 */
  const handleDropFile = async (id: number, targetFolderId: number | null) => {
    try {
      await moveFile(id, targetFolderId ?? 0)
      await load()
    } catch { /* 目标校验失败（如文件夹不存在）时忽略 */ }
  }

  /** 拖拽移动：文件夹到目标文件夹（null 表示根目录）；循环引用由后端拦截。 */
  const handleDropFolder = async (id: number, targetFolderId: number | null) => {
    try {
      await moveFolder(id, targetFolderId ?? 0)
      await load()
    } catch { /* 循环引用等由后端返回错误，保持原样 */ }
  }

  /** 删除单个文件（带确认）。 */
  const requestDeleteFile = (f: FileItem) => {
    setConfirm({
      title: '删除文件',
      message: `确定要删除「${f.name}」吗？此操作不可撤销。`,
      onConfirm: async () => { await handleDeleteFile(f.id); if (selectedIds.has(f.id)) { selectedIds.delete(f.id); setSelectedIds(new Set(selectedIds)) } },
    })
  }

  /** 删除单个文件夹（带确认）。 */
  const requestDeleteFolder = (folder: Folder) => {
    setConfirm({
      title: '删除文件夹',
      message: `确定要删除文件夹「${folder.name}」及其全部内容吗？此操作不可撤销。`,
      onConfirm: async () => { await handleDeleteFolder(folder.id) },
    })
  }

  /** 批量删除选中文件（带确认）。 */
  const requestDeleteSelected = () => {
    const count = selectedIds.size
    setConfirm({
      title: '批量删除',
      message: `确定要删除选中的 ${count} 个文件吗？此操作不可撤销。`,
      onConfirm: async () => { await handleDeleteFiles() },
    })
  }

  /** 带 token 下载文件（浏览器直接 href 不会携带 Authorization，会 401）。 */
  const handleDownload = async (f: FileItem) => {
    const token = localStorage.getItem('token')
    const resp = await fetch(getDownloadUrl(f.id), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!resp.ok) return
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = f.name
    a.click()
    URL.revokeObjectURL(url)
  }

  /** 提交搜索：在主区显示搜索结果视图。 */
  const handleSearch = async () => {
    const q = searchInput.trim()
    if (!q) return
    setQuery(q)
    setSearchLoading(true)
    try {
      setSearchResults(await searchFiles(q))
    } catch {
      setSearchResults([])
    }
    setSearchLoading(false)
  }

  /** 清空搜索，返回文件列表。 */
  const handleClearSearch = () => {
    setSearchInput('')
    setQuery('')
    setSearchResults([])
  }

  /** 创建分享并打开分享弹窗。 */
  const handleShare = async (f: FileItem) => {
    try {
      const res = await createShare(f.id)
      if (res) setShare({ id: res.id, token: res.token, file: f })
    } catch { /* 创建失败忽略 */ }
  }

  /** AI 总结：交由右侧 Copilot 抽屉新建会话执行（抽屉已注入用户 AI 配置头）。 */
  const handleSummarizeFile = (f: FileItem) => {
    setCopilotOpen(true)
    setSummaryRequest((prev) => ({ file: f, nonce: (prev?.nonce ?? 0) + 1 }))
  }

  /** 取消分享。 */
  const handleCancelShare = async (id: number) => {
    await deleteShare(id)
  }

  /** 切换单个文件的索引状态（已索引 → 取消，否则建立）。 */
  const handleToggleIndexFile = async (f: FileItem) => {
    const indexed = indexedMap[f.id] === true
    try {
      if (indexed) {
        const res = await unindexFile(f.id)
        if (res?.status === 'ok') {
          updateIndexed(f.id, false)
          showToast(`已取消「${f.name}」索引`)
        } else showToast(`取消「${f.name}」索引失败`)
      } else {
        showToast(`正在为「${f.name}」建立索引…`)
        const res = await indexFile(f.id, f.name)
        if (res?.status === 'ok') {
          updateIndexed(f.id, true)
          showToast(`「${f.name}」索引完成`)
        } else if (res?.status === 'skipped') {
          const reason = res.reason === 'file too large' ? '超过 100MB，无法索引' : '暂不支持或无文本内容'
          showToast(`「${f.name}」跳过（${reason}）`)
        } else showToast(`「${f.name}」索引失败`)
      }
    } catch {
      showToast('索引请求失败')
    }
  }

  /** 切换文件夹索引状态（递归；全部已索引 → 取消，否则建立，跳过已索引）。 */
  const handleToggleIndexFolder = async (folder: Folder, allIndexed: boolean) => {
    try {
      if (allIndexed) {
        showToast(`正在取消「${folder.name}」索引…`)
        const res = await unindexFolder(folder.id)
        showToast(`已取消 ${res?.removed ?? 0} 个文件索引`)
      } else {
        showToast(`正在为「${folder.name}」建立索引…`)
        const res = await indexFolder(folder.id)
        if (res?.status === 'ok') {
          const trunc = res.truncated ? `，另有 ${res.truncated} 个未处理` : ''
          showToast(`已索引 ${res.indexed} 个，跳过 ${res.skipped} 个${trunc}`)
        } else showToast('文件夹索引失败')
      }
    } catch {
      showToast('索引请求失败')
    }
  }

  /** 多选批量索引：全部已索引 → 取消；否则仅对未索引的建立。 */
  const handleEmbedSelected = async () => {
    const ids = [...selectedIds]
    if (ids.length === 0) return
    const MAX = 100
    const truncated = ids.length > MAX
    const targets = truncated ? ids.slice(0, MAX) : ids

    const allIndexed = targets.every((id) => indexedMap[id] === true)
    const toIndex = allIndexed ? [] : targets.filter((id) => indexedMap[id] !== true)
    const toUnindex = allIndexed ? targets : []

    showToast(allIndexed
      ? `正在取消 ${toUnindex.length} 个文件索引…`
      : `正在为 ${toIndex.length} 个文件建立索引…`)

    const stats = { ok: 0, skipped: 0, failed: 0 }
    const CONCURRENCY = 4
    let cursor = 0
    const queue = allIndexed ? toUnindex : toIndex
    const worker = async () => {
      while (true) {
        const fid = queue[cursor++]
        if (fid === undefined) return
        try {
          const res = allIndexed ? await unindexFile(fid) : await indexFile(fid)
          if (res?.status === 'ok') { stats.ok += 1; updateIndexed(fid, allIndexed ? false : true) }
          else if (!allIndexed && res?.status === 'skipped') stats.skipped += 1
          else stats.failed += 1
        } catch {
          stats.failed += 1
        }
      }
    }
    const workers = Array.from({ length: CONCURRENCY }, () => worker())
    await Promise.all(workers)

    const extra = truncated ? `，另有 ${ids.length - MAX} 个未处理` : ''
    showToast(allIndexed
      ? `已取消 ${stats.ok} 个索引，失败 ${stats.failed} 个${extra}`
      : `已索引 ${stats.ok} 个，跳过 ${stats.skipped} 个，失败 ${stats.failed} 个${extra}`)
  }

  /** 切换单文件选择。 */
  const toggleSelect = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  /** 全选 / 取消全选。 */
  const toggleSelectAll = () => {
    setSelectedIds((prev) => prev.size === files.length ? new Set() : new Set(files.map((f) => f.id)))
  }

  const allSelected = files.length > 0 && selectedIds.size === files.length
  /** 选中文件时自动展开 Copilot。 */
  useEffect(() => {
    if (selectedIds.size > 0 && !copilotOpen) setCopilotOpen(true)
  }, [selectedIds, copilotOpen])

  return (
    <div className="h-screen flex overflow-hidden bg-canvas">
      <Sidebar
        active={section}
        onNavigate={setSection}
        counts={{ files: files.length, tasks: tasks.length }}
        collapsed={sidebarCollapsed}
        onToggle={() => setSidebarCollapsed((c) => !c)}
      />

      {/* 中央主区 */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* 顶栏 */}
        <header className="shrink-0 bg-white/80 backdrop-blur border-b border-line px-5 h-16 flex items-center gap-4">
          <div className="flex items-center gap-2.5 shrink-0">
            {section === 'files' ? <IconFolder size={18} className="text-ink-mute" /> : <IconInbox size={18} className="text-ink-mute" />}
            <h1 className="font-display font-extrabold text-ink">{section === 'files' ? '全部文件' : '任务列表'}</h1>
          </div>

          {/* 搜索框（仅「全部文件」视图展示） */}
          {section === 'files' && (
            <div className="panel flex items-center gap-2 px-3 py-2 flex-1 min-w-[180px] max-w-sm">
              <IconSearch size={15} className="text-ink-mute shrink-0" />
              <input
                className="w-full bg-transparent text-sm focus:outline-none placeholder:text-ink-mute"
                placeholder="搜索文件，回车确认"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleSearch() }}
              />
            </div>
          )}

          <div className="flex-1" />
          {profile && <QuotaBadge used={profile.storage_used} limit={profile.storage_limit} />}
          <button
            onClick={() => setCopilotOpen((o) => !o)}
            className={`${copilotOpen ? 'btn-ghost' : 'btn-primary'} !py-2`}
          >
            <IconSpark size={15} />
            <span className="hidden sm:inline">AI Copilot</span>
            {copilotOpen && <span className="hidden sm:inline text-[11px] opacity-70">跟随文件</span>}
            {!copilotOpen && selectedIds.size > 0 && (
              <span className="ml-0.5 w-4 h-4 rounded-full bg-white/25 text-[10px] flex items-center justify-center">
                {selectedIds.size}
              </span>
            )}
          </button>
        </header>

        {/* 工具条（新建文件夹 + 上传，仅「全部文件」视图展示） */}
        {section === 'files' && (
          <div className="shrink-0 px-5 pt-5 flex flex-wrap items-center gap-3 animate-rise">
            <div className="panel flex items-center gap-1 p-1.5 flex-1 min-w-[220px] max-w-sm">
              <IconPlus size={17} className="text-ink-mute ml-2.5 shrink-0" />
              <input
                className="w-full bg-transparent px-2 py-1.5 text-sm focus:outline-none placeholder:text-ink-mute"
                placeholder="新文件夹名，回车创建"
                value={newFolderName}
                onChange={(e) => setNewFolderName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleCreateFolder() }}
              />
            </div>
            <button onClick={handleCreateFolder} className="btn-primary !py-2.5" disabled={!newFolderName.trim()}>
              <IconPlus size={16} /> 新建文件夹
            </button>
            <div className="flex-1" />
            <label className={`btn-ghost !py-2.5 cursor-pointer ${activeUploads > 0 ? 'opacity-90 pointer-events-none' : ''}`}>
              <IconUpload size={16} />
              {activeUploads > 0 ? `上传中 ${activeUploads}…` : `上传文件${tasks.length > 0 ? ` (${tasks.length})` : ''}`}
              <input type="file" className="hidden" multiple onChange={handleUpload} />
            </label>
          </div>
        )}

        {/* 内容滚动区 */}
        <div className="flex-1 overflow-y-auto px-5 pb-6">
          {query ? (
            <SearchResults
              query={query}
              results={searchResults}
              loading={searchLoading}
              onClear={handleClearSearch}
              onPreview={setPreview}
              onDownload={handleDownload}
            />
          ) : section === 'files' ? (
            <>
              <section className="mt-5 animate-rise delay-2">
                <div className="flex items-center gap-3 mb-2.5">
                  <span className="chip-soft">全部文件 · {files.length}</span>
                  {files.length > 0 && (
                    <button onClick={toggleSelectAll} className="text-xs text-brand-deep hover:underline">
                      {allSelected ? '取消全选' : '全选'}
                    </button>
                  )}
                  {selectedIds.size > 0 && (
                    <>
                      <span className="chip-brand">已选 {selectedIds.size} 项</span>
                      {selectedIds.size > 0 && [...selectedIds].every((id) => indexedMap[id] === true) && (
                        <button onClick={handleEmbedSelected} className="text-xs text-brand-deep hover:underline">
                          取消索引
                        </button>
                      )}
                      {selectedIds.size > 0 && ![...selectedIds].every((id) => indexedMap[id] === true) && (
                        <button onClick={handleEmbedSelected} className="text-xs text-brand-deep hover:underline">
                          批量建立索引
                        </button>
                      )}
                      <button onClick={requestDeleteSelected} className="text-xs text-danger hover:underline">
                        批量删除
                      </button>
                    </>
                  )}
                </div>

                <FileTree
                  folders={folders}
                  rootFiles={files.filter((f) => f.folder_id == null)}
                  selectedIds={selectedIds}
                  onToggle={toggleSelect}
                  onDeleteFile={requestDeleteFile}
                  onDeleteFolder={requestDeleteFolder}
                  onDownload={handleDownload}
                  onPreview={setPreview}
                  onShareFile={handleShare}
                  onSummarizeFile={handleSummarizeFile}
                  indexedMap={indexedMap}
                  onFilesVisible={handleFilesVisible}
                  onToggleIndexFile={handleToggleIndexFile}
                  onToggleIndexFolder={handleToggleIndexFolder}
                  onRenameFile={(id, name) => setDialog({ mode: 'rename-file', id, name })}
                  onMoveFile={(id, name) => setDialog({ mode: 'move-file', id, name })}
                  onRenameFolder={(id, name) => setDialog({ mode: 'rename-folder', id, name })}
                  onMoveFolder={(id, name) => setDialog({ mode: 'move-folder', id, name })}
                  onDropFile={handleDropFile}
                  onDropFolder={handleDropFolder}
                  refreshKey={treeEpoch}
                />
              </section>
            </>
          ) : (
            <section className="mt-5 animate-rise">
              <TaskList tasks={tasks} />
            </section>
          )}
        </div>
      </main>

      {/* 右侧 AI Copilot 面板 */}
      <Copilot
        open={copilotOpen}
        onClose={() => setCopilotOpen(false)}
        selected={selectedFiles}
        summaryRequest={summaryRequest}
        onSummaryHandled={() => setSummaryRequest(null)}
      />

      {/* 文件预览 */}
      <PreviewModal file={preview} onClose={() => setPreview(null)} onDownload={handleDownload} />

      {/* 分享弹窗 */}
      <ShareDialog share={share} onClose={() => setShare(null)} onCancel={handleCancelShare} />

      {/* 重命名 / 移动对话框 */}
      <ActionDialog
        open={dialog !== null}
        mode={dialog ? dialog.mode : 'rename-file'}
        currentName={dialog ? dialog.name : ''}
        folders={folders}
        onClose={() => setDialog(null)}
        onRename={handleRename}
        onMove={handleMove}
      />

      {/* 删除确认 */}
      {confirm && (
        <ConfirmDialog
          open
          title={confirm.title}
          message={confirm.message}
          confirmText="删除"
          onConfirm={() => confirm.onConfirm().catch(() => {})}
          onClose={() => setConfirm(null)}
        />
      )}

      {/* 轻量 toast 提示 */}
      {toast && (
        <div className="fixed top-4 left-1/2 -translate-x-1/2 z-[60] px-4 py-2.5 bg-ink text-white text-sm rounded-xl shadow-lift animate-rise">
          {toast}
        </div>
      )}
    </div>
  )
}