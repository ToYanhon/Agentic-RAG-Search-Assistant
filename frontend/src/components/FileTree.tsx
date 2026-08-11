/** 文件树视图：递归展示目录层级与文件，支持多选（联动 Copilot）、行内操作菜单与拖拽移动。 */
import { useEffect, useState } from 'react'
import { getFolderTree } from '../api/folders'
import { getFolderIndexStatus } from '../api/agent'
import { formatBytes } from '../utils/format'
import { fileTone } from '../utils/preview'
import { DND_MIME, decodeDnD, encodeDnD } from '../utils/dnd'
import type { FileItem, Folder } from '../types'
import {
  IconChevronRight, IconDownload, IconEye, IconFile, IconFileText, IconFolder, IconFolderMove, IconMore, IconShare, IconSpark, IconTrash, IconWand,
} from './Icons'
import ContextMenu, { type MenuItem } from './ContextMenu'

interface FileTreeProps {
  folders: Folder[]
  rootFiles: FileItem[]
  selectedIds: Set<number>
  /** 文件索引状态缓存（id → 已建立索引）。 */
  indexedMap: Record<number, boolean>
  /** 可见文件 id 上报（根 + 已展开子树），用于批量拉取索引状态。 */
  onFilesVisible: (ids: number[]) => void
  onToggle: (id: number) => void
  onDeleteFile: (f: FileItem) => void
  onDeleteFolder: (folder: Folder) => void
  onDownload: (f: FileItem) => void
  onPreview: (f: FileItem) => void
  onShareFile: (f: FileItem) => void
  onSummarizeFile: (f: FileItem) => void
  onToggleIndexFile: (f: FileItem) => void
  onRenameFile: (id: number, currentName: string) => void
  onMoveFile: (id: number, currentName: string) => void
  onRenameFolder: (id: number, currentName: string) => void
  onMoveFolder: (id: number, currentName: string) => void
  onToggleIndexFolder: (folder: Folder, allIndexed: boolean) => void
  /** 拖拽落点：targetFolderId 为 null 表示移到根目录。 */
  onDropFile: (id: number, targetFolderId: number | null) => void
  onDropFolder: (id: number, targetFolderId: number | null) => void
  /** 变化时强制刷新已展开子树（如移动/上传后）。 */
  refreshKey?: number
}

/* ---------------- 文件叶子行 ---------------- */
function FileRow({ f, depth, selected, indexed, onToggle, onDelete, onDownload, onPreview, onShare, onSummarize, onToggleIndex, onRename, onMove }: {
  f: FileItem
  depth: number
  selected: boolean
  indexed: boolean
  onToggle: () => void
  onDelete: () => void
  onDownload: () => void
  onPreview: () => void
  onShare: () => void
  onSummarize: () => void
  onToggleIndex: () => void
  onRename: () => void
  onMove: () => void
}) {
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null)
  const [dragging, setDragging] = useState(false)

  const openMenu = (e: React.MouseEvent<HTMLButtonElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    setMenu({ x: rect.left, y: rect.bottom + 4 })
  }

  const items: MenuItem[] = [
    { key: 'preview', label: '预览', icon: <IconEye size={15} />, onClick: onPreview },
    { key: 'summarize', label: 'AI 总结', icon: <IconFileText size={15} />, onClick: onSummarize },
    { key: 'embed', label: indexed ? '取消索引' : '建立索引', icon: <IconSpark size={15} />, onClick: onToggleIndex },
    { key: 'rename', label: '重命名', icon: <IconWand size={15} />, onClick: onRename },
    { key: 'move', label: '移动', icon: <IconFolderMove size={15} />, onClick: onMove },
    { key: 'share', label: '分享', icon: <IconShare size={15} />, onClick: onShare },
    { key: 'download', label: '下载', icon: <IconDownload size={15} />, onClick: onDownload },
    { key: 'del', label: '删除', icon: <IconTrash size={15} />, onClick: onDelete, danger: true },
  ]

  return (
    <>
      <div
        className={`group flex items-center gap-2 pr-2 py-1.5 rounded-lg cursor-pointer transition-colors ${selected ? 'bg-brand-pale' : 'hover:bg-canvas/70'} ${dragging ? 'opacity-50' : ''}`}
        style={{ paddingLeft: depth * 20 + 12 }}
        onClick={onToggle}
        draggable
        onDragStart={(e) => { e.dataTransfer.setData(DND_MIME, encodeDnD({ kind: 'file', id: f.id })); e.dataTransfer.effectAllowed = 'move'; setDragging(true) }}
        onDragEnd={() => setDragging(false)}
        title="拖到文件夹上移动"
      >
        <button
          onClick={(e) => { e.stopPropagation(); onToggle() }}
          className={`w-[18px] h-[18px] shrink-0 rounded border-2 transition-colors flex items-center justify-center ${selected ? 'bg-brand border-brand' : 'border-line bg-white'}`}
          aria-label="选择文件"
        >
          {selected && (
            <svg viewBox="0 0 20 20" fill="none" stroke="white" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" className="w-3 h-3"><path d="m5 10.5 3.2 3.2L15 7.5" /></svg>
          )}
        </button>
        <span className={`w-7 h-7 rounded-md inline-flex items-center justify-center shrink-0 ${fileTone(f.name)}`}>
          <IconFile size={15} />
        </span>
        <span className="flex-1 min-w-0 text-sm text-ink truncate">{f.name}</span>
        <span className="text-xs text-ink-mute shrink-0">{formatBytes(f.size)}</span>
        <button
          onClick={(e) => { e.stopPropagation(); onPreview() }}
          className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
          title="预览"
        >
          <IconEye size={15} />
        </button>
        <button onClick={openMenu} className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0" title="更多">
          <IconMore size={15} />
        </button>
      </div>
      {menu && <ContextMenu x={menu.x} y={menu.y} items={items} onClose={() => setMenu(null)} />}
    </>
  )
}

/* ---------------- 文件夹节点 ---------------- */
interface NodeProps {
  folder: Folder
  depth: number
  expandedIds: Set<number>
  selectedIds: Set<number>
  indexedMap: Record<number, boolean>
  onFilesVisible: (ids: number[]) => void
  onToggleExpand: (id: number) => void
  onToggle: (id: number) => void
  onDeleteFile: (f: FileItem) => void
  onDeleteFolder: (folder: Folder) => void
  onDownload: (f: FileItem) => void
  onPreview: (f: FileItem) => void
  onShareFile: (f: FileItem) => void
  onSummarizeFile: (f: FileItem) => void
  onToggleIndexFile: (f: FileItem) => void
  onRenameFile: (id: number, currentName: string) => void
  onMoveFile: (id: number, currentName: string) => void
  onRenameFolder: (id: number, currentName: string) => void
  onMoveFolder: (id: number, currentName: string) => void
  onToggleIndexFolder: (folder: Folder, allIndexed: boolean) => void
  onDropFile: (id: number, targetFolderId: number | null) => void
  onDropFolder: (id: number, targetFolderId: number | null) => void
  refreshKey: number
}

function FolderNode(props: NodeProps) {
  const { folder, depth, expandedIds, selectedIds, indexedMap, onFilesVisible, onToggleExpand, onToggle, onDeleteFile, onDeleteFolder, onDownload, onPreview, onShareFile, onSummarizeFile, onToggleIndexFile, onRenameFile, onMoveFile, onRenameFolder, onMoveFolder, onToggleIndexFolder, onDropFile, onDropFolder, refreshKey } = props
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [dragOver, setDragOver] = useState(false)
  const [sub, setSub] = useState<{ folders?: Folder[]; files?: FileItem[] }>({})
  // 本文件夹是否全部文件已索引（点开菜单时查询）
  const [folderAllIndexed, setFolderAllIndexed] = useState(false)

  const expanded = expandedIds.has(folder.id)

  // 展开时懒加载子树；refreshKey 变化（移动/上传后）时对已展开节点强制刷新。
  useEffect(() => {
    if (!expanded) return
    let cancelled = false
    getFolderTree(folder.id).then((res) => {
      if (cancelled) return
      if (res.data) {
        setSub({ folders: res.data.children ?? [], files: res.data.files ?? [] })
      }
    })
    return () => { cancelled = true }
  }, [expanded, folder.id, refreshKey])

  // 子树文件可见 → 上报索引状态查询
  const childFolders = sub.folders ?? folder.children ?? []
  const childFiles = sub.files ?? folder.files ?? []
  useEffect(() => {
    if (childFiles.length > 0) onFilesVisible(childFiles.map((f) => f.id))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [childFiles.length])

  const total = childFolders.length + childFiles.length

  const openFolderMenu = async (e: React.MouseEvent<HTMLButtonElement>) => {
    e.stopPropagation()
    const rect = e.currentTarget.getBoundingClientRect()
    try {
      const res = await getFolderIndexStatus(folder.id)
      setFolderAllIndexed(res?.all_indexed ?? false)
    } catch {
      setFolderAllIndexed(false)
    }
    setMenu({ x: rect.left, y: rect.bottom + 4 })
  }

  const items: MenuItem[] = [
    { key: 'embed', label: folderAllIndexed ? '取消索引' : '建立索引', icon: <IconSpark size={15} />, onClick: () => onToggleIndexFolder(folder, folderAllIndexed) },
    { key: 'rename', label: '重命名', icon: <IconWand size={15} />, onClick: () => onRenameFolder(folder.id, folder.name) },
    { key: 'move', label: '移动', icon: <IconFolderMove size={15} />, onClick: () => onMoveFolder(folder.id, folder.name) },
    { key: 'del', label: '删除文件夹', icon: <IconTrash size={15} />, onClick: () => onDeleteFolder(folder), danger: true },
  ]

  /** 处理拖入本文件夹：文件 → 移入；文件夹 → 非自身时移入。 */
  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    setDragOver(false)
    const p = decodeDnD(e.dataTransfer.getData(DND_MIME))
    if (!p) return
    if (p.kind === 'file') onDropFile(p.id, folder.id)
    else if (p.kind === 'folder' && p.id !== folder.id) onDropFolder(p.id, folder.id)
  }

  return (
    <div
      className="relative"
      onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; e.stopPropagation(); setDragOver(true) }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => { e.stopPropagation(); handleDrop(e) }}
    >
      <div
        className={`group flex items-center gap-2 pr-2 py-2 rounded-lg cursor-pointer transition-colors text-ink-soft hover:text-ink ${dragging ? 'opacity-50' : ''} ${dragOver ? 'ring-2 ring-brand/50 bg-brand-pale text-brand-deep' : 'hover:bg-canvas/70'}`}
        style={{ paddingLeft: depth * 20 + 8 }}
        onClick={() => onToggleExpand(folder.id)}
        draggable
        onDragStart={(e) => { e.dataTransfer.setData(DND_MIME, encodeDnD({ kind: 'folder', id: folder.id })); e.dataTransfer.effectAllowed = 'move'; setDragging(true) }}
        onDragEnd={() => setDragging(false)}
        title="拖到其他文件夹上移动"
      >
        <span className={`w-4 h-4 shrink-0 flex items-center justify-center text-ink-mute transition-transform ${expanded ? 'rotate-90' : ''}`}>
          <IconChevronRight size={14} />
        </span>
        <span className="w-7 h-7 rounded-lg bg-brand-soft text-brand-deep inline-flex items-center justify-center shrink-0">
          <IconFolder size={15} />
        </span>
        <span className="flex-1 min-w-0 text-sm font-medium truncate">{folder.name}</span>
        <span className="text-xs text-ink-mute shrink-0">{total} 项</span>
        <button
          onClick={openFolderMenu}
          className="icon-btn !p-0 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
          title="更多"
        >
          <IconMore size={15} />
        </button>
      </div>
      {menu && <ContextMenu x={menu.x} y={menu.y} items={items} onClose={() => setMenu(null)} />}

      {expanded && (
        <div className="relative ml-[17px] border-l border-line">
          {childFolders.map((c) => (
            <FolderNode
              key={c.id}
              folder={c}
              depth={depth + 1}
              expandedIds={expandedIds}
              selectedIds={selectedIds}
              indexedMap={indexedMap}
              onFilesVisible={onFilesVisible}
              onToggleExpand={onToggleExpand}
              onToggle={onToggle}
              onDeleteFile={onDeleteFile}
              onDeleteFolder={onDeleteFolder}
              onDownload={onDownload}
              onPreview={onPreview}
              onShareFile={onShareFile}
              onSummarizeFile={onSummarizeFile}
              onToggleIndexFile={onToggleIndexFile}
              onRenameFile={onRenameFile}
              onMoveFile={onMoveFile}
              onRenameFolder={onRenameFolder}
              onMoveFolder={onMoveFolder}
              onToggleIndexFolder={onToggleIndexFolder}
              onDropFile={onDropFile}
              onDropFolder={onDropFolder}
              refreshKey={refreshKey}
            />
          ))}
          {childFiles.map((f) => (
            <FileRow
              key={f.id}
              f={f}
              depth={depth + 1}
              selected={selectedIds.has(f.id)}
              indexed={indexedMap[f.id] === true}
              onToggle={() => onToggle(f.id)}
              onDelete={() => onDeleteFile(f)}
              onDownload={() => onDownload(f)}
              onPreview={() => onPreview(f)}
              onShare={() => onShareFile(f)}
              onSummarize={() => onSummarizeFile(f)}
              onToggleIndex={() => onToggleIndexFile(f)}
              onRename={() => onRenameFile(f.id, f.name)}
              onMove={() => onMoveFile(f.id, f.name)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/* ---------------- 根视图 ---------------- */
export default function FileTree({ folders, rootFiles, selectedIds, indexedMap, onFilesVisible, onToggle, onDeleteFile, onDeleteFolder, onDownload, onPreview, onShareFile, onSummarizeFile, onToggleIndexFile, onRenameFile, onMoveFile, onRenameFolder, onMoveFolder, onToggleIndexFolder, onDropFile, onDropFolder, refreshKey = 0 }: FileTreeProps) {
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set())
  const [rootOver, setRootOver] = useState(false)

  // 根文件可见 → 上报索引状态查询（子树文件由 FolderNode 上报）
  useEffect(() => {
    if (rootFiles.length > 0) onFilesVisible(rootFiles.map((f) => f.id))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootFiles.length])

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  /** 拖到根目录区域：移到根（targetFolderId = null）。 */
  const handleRootDrop = (e: React.DragEvent<HTMLDivElement>) => {
    setRootOver(false)
    const p = decodeDnD(e.dataTransfer.getData(DND_MIME))
    if (!p) return
    if (p.kind === 'file') onDropFile(p.id, null)
    else if (p.kind === 'folder') onDropFolder(p.id, null)
  }

  return (
    <div
      className="panel p-2 overflow-hidden"
      onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; setRootOver(true) }}
      onDragLeave={() => setRootOver(false)}
      onDrop={handleRootDrop}
    >
      {/* 根目录拖放提示：整个面板都可拖放移至根，文件夹节点会拦截自身区域的拖放 */}
      <div
        className={`mx-0.5 mb-1.5 rounded-lg border border-dashed text-center text-xs py-1.5 select-none transition-colors ${rootOver ? 'border-brand bg-brand-pale text-brand-deep' : 'border-line text-ink-mute'}`}
        title="将文件或文件夹拖到这里移动到根目录"
      >
        ⇣ 拖到此处移动到根目录
      </div>

      {rootFiles.length > 0 && (
        <div className="pb-1 mb-1 border-b border-line">
          {rootFiles.map((f) => (
            <FileRow
              key={f.id}
              f={f}
              depth={0}
              selected={selectedIds.has(f.id)}
              indexed={indexedMap[f.id] === true}
              onToggle={() => onToggle(f.id)}
              onDelete={() => onDeleteFile(f)}
              onDownload={() => onDownload(f)}
              onPreview={() => onPreview(f)}
              onShare={() => onShareFile(f)}
              onSummarize={() => onSummarizeFile(f)}
              onToggleIndex={() => onToggleIndexFile(f)}
              onRename={() => onRenameFile(f.id, f.name)}
              onMove={() => onMoveFile(f.id, f.name)}
            />
          ))}
        </div>
      )}

      {folders.map((fd) => (
        <FolderNode
          key={fd.id}
          folder={fd}
          depth={0}
          expandedIds={expandedIds}
          selectedIds={selectedIds}
          indexedMap={indexedMap}
          onFilesVisible={onFilesVisible}
          onToggleExpand={toggleExpand}
          onToggle={onToggle}
          onDeleteFile={onDeleteFile}
          onDeleteFolder={onDeleteFolder}
          onDownload={onDownload}
          onPreview={onPreview}
          onShareFile={onShareFile}
          onSummarizeFile={onSummarizeFile}
          onToggleIndexFile={onToggleIndexFile}
          onRenameFile={onRenameFile}
          onMoveFile={onMoveFile}
          onRenameFolder={onRenameFolder}
          onMoveFolder={onMoveFolder}
          onToggleIndexFolder={onToggleIndexFolder}
          onDropFile={onDropFile}
          onDropFolder={onDropFolder}
          refreshKey={refreshKey}
        />
      ))}

      {folders.length === 0 && rootFiles.length === 0 && (
        <div className="py-16 text-center">
          <IconFolder size={28} className="text-ink-mute mx-auto" />
          <p className="text-sm text-ink-mute mt-2">还没有任何内容</p>
        </div>
      )}
    </div>
  )
}
