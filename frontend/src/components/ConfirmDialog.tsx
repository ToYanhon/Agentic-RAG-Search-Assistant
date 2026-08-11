/** 通用确认模态框：用于删除等破坏性操作。 */
import { IconX } from './Icons'

interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmText?: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}

export default function ConfirmDialog({
  open, title, message, confirmText = '确认', danger = true, onConfirm, onClose,
}: ConfirmDialogProps) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-sm bg-white rounded-2xl shadow-lift p-6 animate-rise">
        <button onClick={onClose} className="absolute top-3.5 right-3.5 icon-btn" aria-label="关闭">
          <IconX size={16} />
        </button>
        <h3 className="font-display text-lg font-bold text-ink">{title}</h3>
        <p className="text-sm text-ink-soft mt-2 leading-relaxed">{message}</p>
        <div className="flex items-center justify-end gap-2.5 mt-6">
          <button onClick={onClose} className="btn-ghost !py-2">取消</button>
          <button onClick={() => { onConfirm(); onClose() }} className={`btn !py-2 ${danger ? 'text-white bg-danger hover:bg-danger/90 focus:ring-danger/30' : 'btn-primary'}`}>
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}