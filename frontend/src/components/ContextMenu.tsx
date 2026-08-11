/** 右键/悬浮操作菜单：通用 Popover，配合文件行使用。 */
import { useEffect, useRef } from 'react'

export interface MenuItem {
  key: string
  label: string
  icon?: React.ReactNode
  danger?: boolean
  onClick: () => void
}

interface ContextMenuProps {
  x?: number
  y?: number
  items: MenuItem[]
  onClose: () => void
}

/** 点击外部 / Esc 关闭。 */
export default function ContextMenu({ x, y, items, onClose }: ContextMenuProps) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  if (!x && !y) return null

  // 视口边缘翻转：菜单宽约 168px，在一定边界外靠左
  const left = x && x > window.innerWidth - 220 ? x - 150 : x
  // 垂直方向：菜单高 ≈ items×36 + 容器 padding/border，靠近底边时翻到按钮上方
  const estH = items.length * 36 + 14
  const top = y && y + estH > window.innerHeight - 8 ? Math.max(8, y - estH - 4) : y
  return (
    <div
      ref={ref}
      className="fixed z-50 min-w-[150px] py-1.5 bg-white border border-line rounded-xl shadow-lift animate-rise"
      style={{ left: left ?? 0, top: top ?? 0 }}
    >
      {items.map((it) => (
        <button
          key={it.key}
          onClick={() => { onClose(); it.onClick() }}
          className={`w-full flex items-center gap-2 px-3.5 py-2 text-sm transition-colors
            ${it.danger ? 'text-danger hover:bg-danger/10' : 'text-ink-soft hover:bg-canvas hover:text-ink'}`}
        >
          {it.icon}
          {it.label}
        </button>
      ))}
    </div>
  )
}