/** 左侧导航栏：功能分区导航 + 用户信息。 */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { logout as apiLogout } from '../api/auth'
import { IconFolder, IconInbox, IconLogout, IconSettings, LogoCloud } from './Icons'
import SettingsModal from './SettingsModal'

export type SidebarSection = 'files' | 'tasks'

interface SidebarProps {
  active: SidebarSection
  onNavigate: (s: SidebarSection) => void
  /** 各分区计数徽标 */
  counts?: Partial<{ files: number; tasks: number }>
  collapsed: boolean
  onToggle: () => void
}

export default function Sidebar({ active, onNavigate, counts, collapsed, onToggle }: SidebarProps) {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const items = useMemo<{ key: SidebarSection; label: string; icon: React.ReactNode; count?: number }[]>(() => [
    { key: 'files', label: '全部文件', icon: <IconFolder size={18} />, count: counts?.files },
    { key: 'tasks', label: '任务列表', icon: <IconInbox size={18} />, count: counts?.tasks },
  ], [counts])

  const label = (t: string) => (collapsed ? <span title={t} className="sr-only">{t}</span> : <span>{t}</span>)

  return (
    <aside
      className={`${collapsed ? 'w-[68px]' : 'w-60'} shrink-0 flex flex-col bg-white border-r border-line transition-[width] duration-200`}
    >
      {/* 品牌区 */}
      <div className={`flex items-center border-b border-line ${collapsed ? 'relative justify-center h-16 group' : 'gap-2.5 px-3 h-16'}`}>
        {!collapsed && (
          <Link to="/" className="flex items-center gap-2.5 min-w-0">
            <LogoCloud size={32} />
            <span className="font-display text-lg font-extrabold text-ink">CloudDrive</span>
          </Link>
        )}
        {collapsed && (
          <Link to="/" aria-label="CloudDrive" className="inline-flex"><LogoCloud size={28} /></Link>
        )}
        <button
          onClick={onToggle}
          className={collapsed
            ? 'absolute -right-3 top-1/2 -translate-y-1/2 p-1 rounded-lg text-ink-mute hover:text-ink hover:bg-canvas shadow-card border border-line bg-white opacity-0 group-hover:opacity-100 transition-opacity z-20'
            : 'ml-auto icon-btn hidden sm:inline-flex'}
          title={collapsed ? '展开侧栏' : '收起侧栏'}
        >
          {collapsed
            ? <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="m9.5 6 6 6-6 6" /></svg>
            : <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"><path d="m14.5 6-6 6 6 6" /></svg>}
        </button>
      </div>

      {/* 导航区 */}
      <nav className="flex-1 px-2.5 py-3 space-y-1">
        <p className={`px-2.5 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-ink-mute ${collapsed ? 'text-center' : ''}`}>
          {collapsed ? '···' : '空间'}
        </p>
        {items.map((it) => (
          <button
            key={it.key}
            onClick={() => onNavigate(it.key)}
            title={collapsed ? it.label : undefined}
            className={`w-full flex items-center gap-3 px-2.5 py-2 rounded-xl text-sm transition-colors ${
              active === it.key
                ? 'bg-brand-soft text-brand-deep font-semibold'
                : 'text-ink-soft hover:bg-canvas hover:text-ink'
            } ${collapsed ? 'justify-center' : ''}`}
          >
            <span className="shrink-0">{it.icon}</span>
            {label(it.label)}
            {!collapsed && it.count !== undefined && it.count > 0 && (
              <span className="ml-auto text-[11px] font-medium bg-canvas text-ink-mute rounded-full px-2 py-0.5 border border-line">
                {it.count}
              </span>
            )}
          </button>
        ))}

        {/* 设置：打开设置模态，不切换视图 */}
        <button
          onClick={() => setSettingsOpen(true)}
          title={collapsed ? '设置' : undefined}
          className={`w-full flex items-center gap-3 px-2.5 py-2 rounded-xl text-sm transition-colors text-ink-soft hover:bg-canvas hover:text-ink ${collapsed ? 'justify-center' : ''}`}
        >
          <span className="shrink-0"><IconSettings size={18} /></span>
          {label('设置')}
        </button>
      </nav>

      {/* 底部用户区 */}
      <div className={`border-t border-line flex items-center ${collapsed ? 'relative justify-center h-16 group' : 'p-2.5 gap-2.5'}`}>
        <div className="w-8 h-8 rounded-full bg-gradient-to-br from-brand to-info text-white text-xs font-semibold flex items-center justify-center shrink-0">
          Y
        </div>
        {!collapsed && (
          <button onClick={logout} className="flex-1 flex items-center justify-between text-xs text-ink-mute hover:text-danger transition-colors" title="退出登录">
            <span>yanhon</span>
            <IconLogout size={16} />
          </button>
        )}
        {collapsed && (
          <button onClick={logout} className="absolute -right-3 top-1/2 -translate-y-1/2 p-1 rounded-lg text-ink-mute hover:text-danger shadow-card border border-line bg-white opacity-0 group-hover:opacity-100 transition-opacity z-20" title="退出登录">
            <IconLogout size={14} />
          </button>
        )}
      </div>

      {/* 设置模态框 */}
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </aside>
  )
}

async function logout() {
  // 先通知后端拉黑 token，避免整页导航中断请求；最多等 1.5s，失败也继续本地登出
  try {
    await Promise.race([
      apiLogout(),
      new Promise((r) => setTimeout(r, 1500)),
    ])
  } catch { /* 忽略：本地登出不依赖后端 */ }
  localStorage.removeItem('token')
  window.location.href = '/login'
}