/** 登录页面：用户名密码认证，成功后存储 JWT 并跳转首页。 */
import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { login } from '../api/auth'
import { IconEye, IconEyeOff, IconLock, IconSpark, IconUser, LogoCloud } from '../components/Icons'

export default function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPwd, setShowPwd] = useState(false)
  const [err, setErr] = useState('')
  const nav = useNavigate()

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setErr('')
    try {
      const res = await login(username, password)
      if (res.data) {
        localStorage.setItem('token', res.data.token)
        nav('/')
      }
    } catch {
      setErr('登录失败，请检查用户名和密码')
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-[980px] grid lg:grid-cols-2 gap-8 items-center animate-rise">
        {/* 品牌侧 */}
        <div className="hidden lg:flex flex-col gap-8 pr-10">
          <div className="flex items-center gap-3">
            <LogoCloud size={46} />
            <div>
              <h1 className="font-display text-2xl font-extrabold text-ink leading-none">CloudDrive</h1>
              <p className="text-sm text-ink-mute mt-1">云盘 × 智能文件助手</p>
            </div>
          </div>
          <h2 className="font-display text-4xl leading-tight font-extrabold text-ink">
            你的文件，
            <span className="text-gradient">会思考</span>。
          </h2>
          <p className="text-ink-soft leading-relaxed max-w-md">
            把简历、课件与资料放心存入云端。让 AI 助手帮你检索、总结，
            在对话里随手完成文件管理。
          </p>
          <div className="grid grid-cols-3 gap-3 max-w-md">
            {[
              { t: '云存储', d: '安全同步' },
              { t: 'AI 检索', d: '语义查找' },
              { t: '智能总结', d: '一句话看懂' },
            ].map((f) => (
              <div key={f.t} className="panel p-3.5 hover:shadow-lift hover:-translate-y-0.5 transition-all duration-200">
                <p className="font-semibold text-ink text-sm">{f.t}</p>
                <p className="text-xs text-ink-mute mt-0.5">{f.d}</p>
              </div>
            ))}
          </div>
        </div>

        {/* 表单侧 */}
        <div className="panel-elevated p-8 sm:p-10 w-full max-w-md mx-auto">
          <div className="flex items-center gap-2.5 justify-center lg:hidden mb-6">
            <LogoCloud size={38} />
            <span className="font-display text-xl font-extrabold text-ink">CloudDrive</span>
          </div>
          <div className="flex items-center gap-2 mb-2">
            <span className="chip-brand"><IconSpark size={13} /> 欢迎回来</span>
          </div>
          <h3 className="font-display text-2xl font-extrabold text-ink">登录你的云端空间</h3>
          <p className="text-sm text-ink-soft mt-1.5 mb-7">继续你的文件与 AI 对话之旅</p>

          <form onSubmit={handleSubmit} className="space-y-4">
            {err && (
              <p className="text-sm text-danger bg-danger/10 border border-danger/20 rounded-xl px-4 py-2.5">
                {err}
              </p>
            )}
            <div>
              <label className="field-label"><IconUser size={14} className="inline mr-1 -mt-0.5" />用户名</label>
              <input
                className="field"
                placeholder="输入你的用户名"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>
            <div>
              <label className="field-label"><IconLock size={14} className="inline mr-1 -mt-0.5" />密码</label>
              <div className="relative">
                <input
                  className="field pr-11"
                  type={showPwd ? 'text' : 'password'}
                  placeholder="输入密码"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
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
              登 录
            </button>
          </form>

          <p className="text-center text-sm text-ink-soft mt-6">
            没有账号？
            <Link to="/register" className="font-semibold text-brand-deep hover:text-brand">立即注册</Link>
          </p>

          <div className="flex items-center gap-2 mt-6 pt-5 border-t border-line text-xs text-ink-mute">
            <span className="chip-brand"><IconSpark size={13} /> AI 就绪</span>
            <span className="text-ink-mute">文件 + 对话，一个空间搞定</span>
          </div>
        </div>
      </div>
    </div>
  )
}