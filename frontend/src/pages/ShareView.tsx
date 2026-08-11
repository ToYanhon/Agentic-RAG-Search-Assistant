/** 公开分享落地页：展示分享的文件信息并提供下载（无需登录）。 */
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getShare, getShareDownloadUrl } from '../api/shares'
import { formatBytes } from '../utils/format'
import { fileTone } from '../utils/preview'
import type { FileItem } from '../types'
import { IconDownload, IconFile, IconSpark, LogoCloud } from '../components/Icons'

type Status = 'loading' | 'ok' | 'notfound'

export default function ShareView() {
  const token = window.location.pathname.split('/').filter(Boolean).pop() ?? ''
  const [status, setStatus] = useState<Status>('loading')
  const [file, setFile] = useState<FileItem | null>(null)
  const [downloading, setDownloading] = useState(false)

  useEffect(() => {
    let cancelled = false
    getShare(token).then((f) => {
      if (cancelled) return
      setFile(f)
      setStatus(f ? 'ok' : 'notfound')
    })
    return () => { cancelled = true }
  }, [token])

  const handleDownload = async () => {
    if (!file) return
    setDownloading(true)
    try {
      const resp = await fetch(getShareDownloadUrl(token))
      if (!resp.ok) return
      const blob = await resp.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = file.name
      a.click()
      URL.revokeObjectURL(url)
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-md animate-rise">
        {/* 品牌头 */}
        <div className="flex items-center gap-2.5 justify-center mb-8">
          <LogoCloud size={40} />
          <div>
            <h1 className="font-display text-xl font-extrabold text-ink leading-none">CloudDrive</h1>
            <p className="text-xs text-ink-mute mt-1">云盘 × 智能文件助手</p>
          </div>
        </div>

        <div className="panel-elevated p-8">
          {status === 'loading' && (
            <div className="py-12 flex items-center justify-center">
              <div className="w-8 h-8 border-[3px] border-brand/25 border-t-brand rounded-full animate-spin" />
            </div>
          )}

          {status === 'notfound' && (
            <div className="py-10 text-center">
              <IconFile size={34} className="text-ink-mute mx-auto" />
              <p className="text-ink font-semibold mt-4">分享不存在或已过期</p>
              <p className="text-sm text-ink-mute mt-1">请联系分享者确认链接是否有效</p>
              <Link to="/" className="inline-block mt-6 btn-ghost !py-2">返回首页</Link>
            </div>
          )}

          {status === 'ok' && file && (
            <>
              <div className="flex items-center gap-2 mb-4">
                <span className="chip-brand"><IconSpark size={13} /> 文件分享</span>
              </div>
              <div className="flex items-center gap-4">
                <span className={`w-14 h-14 rounded-xl inline-flex items-center justify-center shrink-0 ${fileTone(file.name)}`}>
                  <IconFile size={28} />
                </span>
                <div className="min-w-0">
                  <p className="text-lg font-semibold text-ink truncate">{file.name}</p>
                  <p className="text-sm text-ink-mute mt-1">{formatBytes(file.size)}</p>
                </div>
              </div>
              <button
                onClick={handleDownload}
                disabled={downloading}
                className="btn-primary w-full py-3 mt-7"
              >
                <IconDownload size={16} />
                {downloading ? '下载中…' : '下载文件'}
              </button>
              <p className="text-center text-xs text-ink-mute mt-4">
                通过安全链接分享 · 由 CloudDrive 提供
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
