/** 重命名 / 移动 对话框。 */
import { useEffect, useState } from 'react'
import { IconX } from './Icons'
import type { Folder } from '../types'

interface ActionDialogProps {
  open: boolean
  mode: 'rename-file' | 'rename-folder' | 'move-file' | 'move-folder'
  currentName: string
  folders: Folder[]
  onClose: () => void
  onRename: (name: string) => void
  onMove: (targetFolderId: number | null) => void
}

/** 移动时渲染的目录选择树（仅顶层文件夹，展开子级）。 */
function FolderPicker({ folders, value, onChange }: {
  folders: Folder[]
  value: number | null
  onChange: (id: number | null) => void
}) {
  return (
    <div className="border border-line rounded-xl max-h-56 overflow-y-auto p-1.5 space-y-0.5">
      <button
        onClick={() => onChange(null)}
        className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${value === null ? 'bg-brand-soft text-brand-deep' : 'text-ink-soft hover:bg-canvas'}`}
      >
        📁 根目录
      </button>
      {folders.map((f) => (
        <button
          key={f.id}
          onClick={() => onChange(f.id)}
          className={`w-full text-left px-3 py-2 rounded-lg text-sm transition-colors ${value === f.id ? 'bg-brand-soft text-brand-deep' : 'text-ink-soft hover:bg-canvas'}`}
        >
          📁 {f.name}
        </button>
      ))}
    </div>
  )
}

export default function ActionDialog({ open, mode, currentName, folders, onClose, onRename, onMove }: ActionDialogProps) {
  const [name, setName] = useState(currentName)
  const [target, setTarget] = useState<number | null>(null)

  useEffect(() => {
    if (open) { setName(currentName); setTarget(null) }
  }, [open, currentName])

  if (!open) return null

  const title = mode === 'move-file' || mode === 'move-folder' ? '移动' : '重命名'

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-sm bg-white rounded-2xl shadow-lift p-6 animate-rise">
        <button onClick={onClose} className="absolute top-3.5 right-3.5 icon-btn" aria-label="关闭">
          <IconX size={16} />
        </button>
        <h3 className="font-display text-lg font-bold text-ink">{title}</h3>

        {mode === 'move-file' || mode === 'move-folder' ? (
          <div className="mt-4">
            <p className="text-sm text-ink-soft mb-2">将「{currentName}」移动到：</p>
            <FolderPicker folders={folders} value={target} onChange={setTarget} />
          </div>
        ) : (
          <div className="mt-4">
            <p className="text-sm text-ink-soft mb-2">新名称：</p>
            <input
              className="field"
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && name.trim()) { onRename(name.trim()); onClose() }
              }}
            />
          </div>
        )}

        <div className="flex items-center justify-end gap-2.5 mt-6">
          <button onClick={onClose} className="btn-ghost !py-2">取消</button>
          {mode === 'move-file' || mode === 'move-folder' ? (
            <button onClick={() => { onMove(target); onClose() }} className="btn-primary !py-2">
              移动
            </button>
          ) : (
            <button onClick={() => { if (name.trim()) { onRename(name.trim()); onClose() } }} className="btn-primary !py-2">
              保存
            </button>
          )}
        </div>
      </div>
    </div>
  )
}