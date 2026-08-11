/** 顶栏存储配额展示：已用/上限 + 细进度条。 */
import { formatBytes } from '../utils/format'

interface QuotaBadgeProps {
  used: number
  limit: number
}

export default function QuotaBadge({ used, limit }: QuotaBadgeProps) {
  const pct = limit > 0 ? Math.min(100, Math.round((used / limit) * 1000) / 10) : 0
  const danger = pct >= 90
  return (
    <div className="flex flex-col items-end gap-1 w-[120px] shrink-0" title={`已用 ${formatBytes(used)} / 共 ${formatBytes(limit)}（${pct}%）`}>
      <span className="text-[11px] leading-none text-ink-mute tabular-nums">
        <span className={danger ? 'text-danger' : 'text-ink-soft'}>{formatBytes(used)}</span>
        {' / '}
        {formatBytes(limit)}
      </span>
      <div className="w-full h-[4px] rounded-full bg-canvas border border-line overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-300 ${danger ? 'bg-danger' : 'bg-brand'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
