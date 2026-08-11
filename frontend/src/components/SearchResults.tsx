/** 搜索结果视图：顶栏搜索回车后，主区展示匹配文件列表。 */
import { formatBytes } from '../utils/format'
import { fileTone, previewKind } from '../utils/preview'
import type { FileItem } from '../types'
import { IconDownload, IconEye, IconFile, IconSearch, IconX } from './Icons'

interface SearchResultsProps {
  query: string
  results: FileItem[]
  loading?: boolean
  onClear: () => void
  onPreview: (f: FileItem) => void
  onDownload: (f: FileItem) => void
}

export default function SearchResults({ query, results, loading, onClear, onPreview, onDownload }: SearchResultsProps) {
  return (
    <section className="mt-5 animate-rise">
      <div className="flex items-center gap-3 mb-2.5">
        <span className="chip-brand"><IconSearch size={13} /> 搜索：{query}</span>
        <span className="chip-soft">{results.length} 条</span>
        <button onClick={onClear} className="icon-btn !p-1 text-xs text-ink-mute hover:text-ink" title="清空搜索">
          <IconX size={13} /> 清空
        </button>
      </div>

      <div className="panel p-2">
        {loading && (
          <div className="py-12 flex items-center justify-center">
            <div className="w-7 h-7 border-[3px] border-brand/25 border-t-brand rounded-full animate-spin" />
          </div>
        )}

        {!loading && results.length === 0 && (
          <div className="py-14 text-center">
            <IconFile size={28} className="text-ink-mute mx-auto" />
            <p className="text-sm text-ink-mute mt-2">未找到匹配「{query}」的文件</p>
          </div>
        )}

        {!loading && results.map((f) => (
          <div
            key={f.id}
            className="group flex items-center gap-2 pr-2 py-1.5 rounded-lg cursor-pointer transition-colors hover:bg-canvas/70"
            onClick={() => onPreview(f)}
          >
            <span className={`w-7 h-7 rounded-md inline-flex items-center justify-center shrink-0 ${fileTone(f.name)}`}>
              <IconFile size={15} />
            </span>
            <span className="flex-1 min-w-0 text-sm text-ink truncate">{f.name}</span>
            <span className="text-xs text-ink-mute shrink-0 hidden sm:inline">{f.created_at?.slice(0, 10)}</span>
            <span className="text-xs text-ink-mute shrink-0">{formatBytes(f.size)}</span>
            <span className="shrink-0 text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-canvas text-ink-mute border border-line">
              {previewKind(f.name) ?? 'file'}
            </span>
            <button
              onClick={(e) => { e.stopPropagation(); onPreview(f) }}
              className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
              title="预览"
            >
              <IconEye size={15} />
            </button>
            <button
              onClick={(e) => { e.stopPropagation(); onDownload(f) }}
              className="icon-btn !p-1.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0"
              title="下载"
            >
              <IconDownload size={15} />
            </button>
          </div>
        ))}
      </div>
    </section>
  )
}
