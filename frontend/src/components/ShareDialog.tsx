/** 分享弹窗：展示分享链接、一键复制、取消分享。 */
import { useEffect, useState } from 'react'
import type { FileItem } from '../types'
import { IconCopy, IconShare, IconX } from './Icons'

export interface ShareState {
  id: number
  token: string
  file: FileItem
}

interface ShareDialogProps {
  share: ShareState | null
  onClose: () => void
  onCancel: (id: number) => Promise<void>
}

export default function ShareDialog({ share, onClose, onCancel }: ShareDialogProps) {
  const [copied, setCopied] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (share) { setCopied(false); setErr('') }
  }, [share])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  if (!share) return null

  const link = `${window.location.origin}/s/${share.token}`

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      // 降级：选中输入框内容
      const el = document.getElementById('share-link') as HTMLInputElement | null
      if (el) { el.select(); document.execCommand('copy') }
      setCopied(true)
    }
  }

  const handleCancel = async () => {
    setCancelling(true)
    setErr('')
    try {
      await onCancel(share.id)
      onClose()
    } catch {
      setErr('取消失败，请重试')
      setCancelling(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-md bg-white rounded-2xl shadow-lift p-6 animate-rise">
        <button onClick={onClose} className="absolute top-3.5 right-3.5 icon-btn" aria-label="关闭">
          <IconX size={16} />
        </button>
        <h3 className="font-display text-lg font-bold text-ink flex items-center gap-2">
          <span className="w-8 h-8 rounded-lg bg-brand-soft text-brand-deep inline-flex items-center justify-center">
            <IconShare size={16} />
          </span>
          分享链接
        </h3>
        <p className="text-sm text-ink-soft mt-2 truncate">「{share.file.name}」</p>

        <div className="mt-4">
          <label className="field-label">链接（任何人都可打开）</label>
          <div className="flex items-center gap-2">
            <input
              id="share-link"
              className="field flex-1 !px-3 !py-2.5 text-[13px] text-ink-soft"
              readOnly
              value={link}
              onFocus={(e) => e.target.select()}
            />
            <button onClick={handleCopy} className={`btn shrink-0 !py-2.5 ${copied ? 'bg-brand-soft text-brand-deep' : 'btn-primary'}`}>
              <IconCopy size={15} />
              {copied ? '已复制' : '复制'}
            </button>
          </div>
          {copied && <p className="text-xs text-brand-deep mt-1.5">链接已复制到剪贴板</p>}
        </div>

        {err && <p className="text-sm text-danger mt-3">{err}</p>}

        <div className="flex items-center justify-between mt-6 pt-4 border-t border-line">
          <button
            onClick={handleCancel}
            disabled={cancelling}
            className="text-xs text-danger hover:underline disabled:opacity-50"
          >
            {cancelling ? '取消中…' : '取消分享'}
          </button>
          <button onClick={onClose} className="btn-ghost !py-2">完成</button>
        </div>
      </div>
    </div>
  )
}
