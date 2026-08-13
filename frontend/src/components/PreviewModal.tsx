/** 文件预览模态框：图片 / PDF / 文本 / 视频 / 不支持提示。 */
import { useEffect, useRef, useState } from 'react'
import { getFileBlob, getFileContent } from '../api/files'
import { formatBytes } from '../utils/format'
import { previewKind, isMarkdown, TEXT_PREVIEW_MAX } from '../utils/preview'
import type { FileItem } from '../types'
import { IconDownload, IconFile, IconX } from './Icons'
import Markdown from './Markdown'

interface PreviewModalProps {
  file: FileItem | null
  onClose: () => void
  onDownload?: (f: FileItem) => void
}

type Status = 'loading' | 'ready' | 'error' | 'unsupported' | 'too-large'

export default function PreviewModal({ file, onClose, onDownload }: PreviewModalProps) {
  const [status, setStatus] = useState<Status>('loading')
  const [text, setText] = useState<string | null>(null)
  const [url, setUrl] = useState<string | null>(null)
  const urlRef = useRef<string | null>(null)

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // 按文件加载预览内容
  useEffect(() => {
    if (!file) return
    let cancelled = false
    const kind = previewKind(file.name)

    if (urlRef.current) { URL.revokeObjectURL(urlRef.current); urlRef.current = null }
    setUrl(null)
    setText(null)
    setStatus(kind === null ? 'unsupported' : kind === 'text' && file.size > TEXT_PREVIEW_MAX ? 'too-large' : 'loading')
    if (kind === null) return
    if (kind === 'text' && file.size > TEXT_PREVIEW_MAX) return

    ;(async () => {
      try {
        if (kind === 'text') {
          // 文本：走读内容接口（按行），避免整文件读入内存
          const res = await getFileContent(file.id, 1, Math.ceil(TEXT_PREVIEW_MAX / 8))
          if (cancelled) return
          const content = res?.data?.content ?? ''
          setText(content.length > TEXT_PREVIEW_MAX ? content.slice(0, TEXT_PREVIEW_MAX) : content)
        } else {
          const blob = await getFileBlob(file.id)
          if (cancelled) return
          const u = URL.createObjectURL(blob)
          urlRef.current = u
          setUrl(u)
        }
        setStatus('ready')
      } catch {
        if (!cancelled) setStatus('error')
      }
    })()

    return () => { cancelled = true }
  }, [file])

  // 卸载时释放对象 URL
  useEffect(() => () => { if (urlRef.current) URL.revokeObjectURL(urlRef.current) }, [])

  if (!file) return null

  const kind = previewKind(file.name)

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-4xl max-h-[85vh] bg-white rounded-2xl shadow-lift flex flex-col animate-rise overflow-hidden">
        {/* 头部 */}
        <div className="shrink-0 flex items-center gap-3 px-5 py-3.5 border-b border-line">
          <span className="w-8 h-8 rounded-lg bg-canvas text-ink-mute inline-flex items-center justify-center shrink-0">
            <IconFile size={16} />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-ink truncate">{file.name}</p>
            <p className="text-[11px] text-ink-mute mt-0.5">{formatBytes(file.size)} · {kind ?? '不可预览'}</p>
          </div>
          {onDownload && (
            <button onClick={() => onDownload(file)} className="btn-ghost !py-2 shrink-0">
              <IconDownload size={15} /> 下载
            </button>
          )}
          <button onClick={onClose} className="icon-btn shrink-0" aria-label="关闭">
            <IconX size={17} />
          </button>
        </div>

        {/* 内容区 */}
        <div className="flex-1 min-h-0 bg-canvas/50 overflow-auto">
          {status === 'loading' && (
            <div className="h-72 flex items-center justify-center">
              <div className="w-8 h-8 border-[3px] border-brand/25 border-t-brand rounded-full animate-spin" />
            </div>
          )}
          {status === 'error' && (
            <div className="h-72 flex flex-col items-center justify-center gap-2 text-ink-mute">
              <IconFile size={30} />
              <p className="text-sm">预览加载失败，请下载后查看</p>
            </div>
          )}
          {status === 'unsupported' && (
            <div className="h-72 flex flex-col items-center justify-center gap-2 text-ink-mute">
              <IconFile size={30} />
              <p className="text-sm">该文件类型暂不支持在线预览，请下载后查看</p>
            </div>
          )}
          {status === 'too-large' && (
            <div className="h-72 flex flex-col items-center justify-center gap-2 text-ink-mute">
              <IconFile size={30} />
              <p className="text-sm">文本文件过大，建议下载后查看</p>
            </div>
          )}
          {status === 'ready' && kind === 'image' && url && (
            <div className="min-h-full flex items-center justify-center p-4">
              <img src={url} alt={file.name} className="max-w-full max-h-[70vh] rounded-lg shadow-card object-contain" />
            </div>
          )}
          {status === 'ready' && kind === 'pdf' && url && (
            <iframe src={url} title={file.name} className="w-full h-[70vh] border-0 bg-white" />
          )}
          {status === 'ready' && kind === 'video' && url && (
            <div className="flex items-center justify-center p-4">
              <video src={url} controls className="max-w-full max-h-[70vh] rounded-lg bg-black" />
            </div>
          )}
          {status === 'ready' && kind === 'text' && isMarkdown(file.name) && (
            <div className="p-5 max-w-none">
              <Markdown>{text ?? ''}</Markdown>
            </div>
          )}
          {status === 'ready' && kind === 'text' && !isMarkdown(file.name) && (
            <pre className="p-5 text-[13px] leading-relaxed text-ink whitespace-pre-wrap break-words font-mono">
              {text}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
