/** 上传任务类型：由 Dashboard 管理队列，本组件仅负责展示。 */
export interface UploadTaskItem {
  id: string
  name: string
  size: number
  progress: number
  status: 'queued' | 'hashing' | 'uploading' | 'instant' | 'done' | 'error'
}

/** 格式化文件大小。 */
const fmtSize = (b: number) =>
  b === 0 ? '0 B' : b < 1024 ? b + ' B' : b < 1048576 ? (b / 1024).toFixed(1) + ' KB' : (b / 1048576).toFixed(1) + ' MB'

const statusText: Record<UploadTaskItem['status'], { label: string; cls: string }> = {
  queued: { label: '排队中', cls: 'text-ink-mute' },
  hashing: { label: '校验中', cls: 'text-brand-deep' },
  uploading: { label: '上传中', cls: 'text-brand-deep' },
  instant: { label: '秒传', cls: 'text-info' },
  done: { label: '完成', cls: 'text-info' },
  error: { label: '失败', cls: 'text-danger' },
}

/** 任务列表视图：展示文件/上传任务及进度。 */
export default function TaskList({ tasks }: { tasks: UploadTaskItem[] }) {
  const active = tasks.filter((t) => t.status === 'queued' || t.status === 'hashing' || t.status === 'uploading').length
  const done = tasks.filter((t) => t.status === 'done').length

  return (
    <div className="panel divide-y divide-line">
      <div className="flex items-center gap-3 px-5 py-4">
        <span className="w-9 h-9 rounded-lg bg-info/10 text-info inline-flex items-center justify-center">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="M4.5 6.5A1.5 1.5 0 0 1 6 5h12a1.5 1.5 0 0 1 1.5 1.5l-2 10a1.5 1.5 0 0 1-1.5 1.5H7a1.5 1.5 0 0 1-1.5-1.5l-1-10Z" /><path d="M4.5 11h4l1.2 1.6a1.5 1.5 0 0 0 1.2.6h1.4a1.5 1.5 0 0 0 1.2-.6l1.2-1.6h3.1" /></svg>
        </span>
        <div className="flex-1">
          <p className="text-sm font-medium text-ink">上传/下载任务</p>
          <p className="text-xs text-ink-mute mt-0.5">实时展示传输队列</p>
        </div>
        <span className="chip-brand">进行中 {active}</span>
        {done > 0 && <span className="chip-soft">已完成 {done}</span>}
      </div>

      {tasks.length === 0 ? (
        <div className="px-5 py-14 text-center">
          <p className="text-ink-mute text-sm">暂无任务</p>
          <p className="text-xs text-ink-mute/70 mt-1">上传文件后会在这里实时显示进度</p>
        </div>
      ) : (
        tasks.map((t) => (
          <div key={t.id} className="flex items-center gap-3.5 px-5 py-3.5">
            <div className="flex-1 min-w-0">
              <div className="flex items-center justify-between gap-3">
                <p className="text-sm text-ink truncate">{t.name}</p>
                <span className={`text-xs shrink-0 ${statusText[t.status].cls}`}>{statusText[t.status].label}</span>
              </div>
              <p className="text-[11px] text-ink-mute mt-0.5">{fmtSize(t.size)}</p>
              {/* 进度条 */}
              <div className="mt-1.5 h-1.5 rounded-full bg-canvas overflow-hidden">
                <div
                  className={`h-full rounded-full transition-[width] duration-300 ${
                    t.status === 'error' ? 'bg-danger'
                    : t.status === 'hashing' ? 'bg-brand animate-pulse'
                    : t.status === 'done' || t.status === 'instant' ? 'bg-info'
                    : 'bg-brand'
                  }`}
                  style={{ width: `${t.progress}%` }}
                />
              </div>
            </div>
            <span className="text-xs text-ink-mute w-9 text-right shrink-0">{t.progress}%</span>
          </div>
        ))
      )}
    </div>
  )
}