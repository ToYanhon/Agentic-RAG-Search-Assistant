/** AI 文件摘要展示弹窗。 */
import { IconX } from './Icons'
import Markdown from './Markdown'

interface SummaryDialogProps {
  open: boolean
  filename: string
  loading: boolean
  content: string
  error?: string
  onClose: () => void
}

export default function SummaryDialog({
  open, filename, loading, content, error, onClose,
}: SummaryDialogProps) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-lg bg-white rounded-2xl shadow-lift animate-rise flex flex-col max-h-[70vh]">
        <div className="flex items-center justify-between px-5 pt-4 pb-3 border-b border-line">
          <h3 className="font-display text-base font-bold text-ink truncate">
            AI 总结 · {filename}
          </h3>
          <button onClick={onClose} className="icon-btn" aria-label="关闭">
            <IconX size={16} />
          </button>
        </div>
        <div className="px-5 py-4 overflow-y-auto text-sm text-ink-soft leading-relaxed">
          {loading ? (
            <div className="flex items-center gap-2 py-6 justify-center text-ink-mute">
              <span className="w-3.5 h-3.5 border-2 border-brand/30 border-t-brand rounded-full animate-spin" />
              正在生成摘要…
            </div>
          ) : error ? (
            <p className="text-danger">{error}</p>
          ) : (
            <Markdown>{content}</Markdown>
          )}
        </div>
      </div>
    </div>
  )
}
