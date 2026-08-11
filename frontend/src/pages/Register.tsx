/** 注册页面：创建新账号后跳转到登录页。 */
import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { register } from '../api/auth'
import { IconEye, IconEyeOff, IconLock, IconMail, IconSpark, IconUser, LogoCloud } from '../components/Icons'

export default function Register() {
  const [form, setForm] = useState({ username: '', email: '', password: '' })
  const [showPwd, setShowPwd] = useState(false)
  const [err, setErr] = useState('')
  const nav = useNavigate()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    try {
      await register(form.username, form.email, form.password)
      nav('/login')
    } catch {
      setErr('注册失败，可能用户名或邮箱已存在')
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-[980px] grid lg:grid-cols-2 gap-8 items-center animate-rise">
        {/* 品牌侧 */}
        <div className="hidden lg:flex flex-col gap-8 pr-10 order-2">
          <div className="flex items-center gap-3">
            <LogoCloud size={46} />
            <div>
              <h1 className="font-display text-2xl font-extrabold text-ink leading-none">CloudDrive</h1>
              <p className="text-sm text-ink-mute mt-1">云盘 × 智能文件助手</p>
            </div>
          </div>
          <h2 className="font-display text-4xl leading-tight font-extrabold text-ink">
            一个空间，<span className="text-gradient">装下所有文件</span>。
          </h2>
          <p className="text-ink-soft leading-relaxed max-w-md">
            注册即拥有独立的云端空间。上传你的文档，
            之后随时能让 AI 帮你找、帮你读、帮你总结。
          </p>
          <ul className="space-y-2.5 max-w-md">
            {['默认 5GB 免费存储', 'AI 助手逐步解锁', '文件夹细分管理'].map((t) => (
              <li key={t} className="flex items-center gap-2.5 text-sm text-ink-soft">
                <span className="w-5 h-5 rounded-full bg-brand-soft text-brand-deep inline-flex items-center justify-center text-xs">✓</span>
                {t}
              </li>
            ))}
          </ul>
        </div>

        {/* 表单侧 */}
        <div className="panel-elevated p-8 sm:p-10 w-full max-w-md mx-auto lg:order-1">
          <div className="flex items-center gap-2.5 justify-center lg:hidden mb-6">
            <LogoCloud size={38} />
            <span className="font-display text-xl font-extrabold text-ink">CloudDrive</span>
          </div>
          <div className="flex items-center gap-2 mb-2">
            <span className="chip-brand"><IconSpark size={13} /> 快速开始</span>
          </div>
          <h3 className="font-display text-2xl font-extrabold text-ink">创建账号</h3>
          <p className="text-sm text-ink-soft mt-1.5 mb-7">几秒钟即可开启云端 + AI 之旅</p>

          <form onSubmit={handleSubmit} className="space-y-4">
            {err && (
              <p className="text-sm text-danger bg-danger/10 border border-danger/20 rounded-xl px-4 py-2.5">
                {err}
              </p>
            )}
            <div>
              <label className="field-label"><IconUser size={14} className="inline mr-1 -mt-0.5" />用户名</label>
              <input className="field" placeholder="设置用户名" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
            </div>
            <div>
              <label className="field-label"><IconMail size={14} className="inline mr-1 -mt-0.5" />邮箱</label>
              <input className="field" type="email" placeholder="you@example.com" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </div>
            <div>
              <label className="field-label"><IconLock size={14} className="inline mr-1 -mt-0.5" />密码</label>
              <div className="relative">
                <input
                  className="field pr-11"
                  type={showPwd ? 'text' : 'password'}
                  placeholder="至少 6 位"
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                />
                <button
                  type="button"
                  onClick={() => setShowPwd((s) => !s)}
                  className="icon-btn !absolute right-1.5 top-1/2 -translate-y-1/2 !p-2"
                  title={showPwd ? '隐藏密码' : '显示密码'}
                  aria-label={showPwd ? '隐藏密码' : '显示密码'}
                >
                  {showPwd ? <IconEyeOff size={16} /> : <IconEye size={16} />}
                </button>
              </div>
            </div>
            <button type="submit" className="btn-primary w-full py-3 mt-2">
              注 册
            </button>
          </form>

          <p className="text-center text-sm text-ink-soft mt-6">
            已有账号？
            <Link to="/login" className="font-semibold text-brand-deep hover:text-brand">直接登录</Link>
          </p>
        </div>
      </div>
    </div>
  )
}