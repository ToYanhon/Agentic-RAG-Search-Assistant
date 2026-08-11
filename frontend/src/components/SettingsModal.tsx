/** 设置模态框：个人资料 / AI 配置。AI 配置按供应商独立，由后端加密存储（apiKey 仅脱敏展示）。 */
import { useCallback, useEffect, useState } from 'react'
import { getProfile, updateProfile, changePassword } from '../api/auth'
import { getLLMConfig, saveLLMConfig, deleteLLMConfig } from '../api/llmConfig'
import {
  getActiveProvider,
  setActiveProvider,
  buildConfigMap,
  PROVIDER_DEFAULT_BASE_URL,
  PROVIDER_MODEL_PRESETS,
  PROVIDER_LABELS,
  type LLMConfigMap,
  type LLMProvider,
} from '../utils/settings'
import type { User } from '../types'
import { IconSettings, IconSpark, IconUser, IconX } from './Icons'

type Tab = 'profile' | 'ai'

interface Msg {
  kind: 'ok' | 'err'
  text: string
}

const PROVIDERS = Object.keys(PROVIDER_LABELS).filter((k) => k !== 'tavily') as LLMProvider[]

/** 单个供应商的编辑草稿（apiKey 输入框为待保存明文，后端不回传明文）。 */
interface ProviderDraft {
  baseUrl: string
  apiKey: string
  model: string
  customMode: boolean
  customModel: string
}

function emptyDraft(): ProviderDraft {
  return { baseUrl: '', apiKey: '', model: '', customMode: false, customModel: '' }
}

function MsgLine({ msg }: { msg: Msg | null }) {
  if (!msg) return null
  return (
    <p className={`text-[13px] rounded-xl px-3 py-2 ${msg.kind === 'ok' ? 'bg-brand-soft text-brand-deep' : 'bg-danger/10 text-danger'}`}>
      {msg.text}
    </p>
  )
}

export default function SettingsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>('profile')

  // 个人资料
  const [profile, setProfile] = useState<User | null>(null)
  const [username, setUsername] = useState('')
  const [profileMsg, setProfileMsg] = useState<Msg | null>(null)
  // 修改密码
  const [oldPwd, setOldPwd] = useState('')
  const [newPwd, setNewPwd] = useState('')
  const [confirmPwd, setConfirmPwd] = useState('')
  const [pwdMsg, setPwdMsg] = useState<Msg | null>(null)
  // AI 配置（按供应商独立）
  const [configs, setConfigs] = useState<LLMConfigMap>({})
  const [active, setActive] = useState<LLMProvider>('openai')
  const [applied, setApplied] = useState<LLMProvider>(getActiveProvider())
  const [drafts, setDrafts] = useState<Record<string, ProviderDraft>>({})
  const [tavily, setTavily] = useState('')
  const [aiMsg, setAiMsg] = useState<Msg | null>(null)

  const draft: ProviderDraft = drafts[active] ?? emptyDraft()
  const activeCfg = configs[active]
  const appliedCfg = configs[applied]

  /** 从后端拉取配置并更新状态，返回 map 供后续 seedDraft 使用。 */
  const reload = useCallback(async (): Promise<LLMConfigMap> => {
    const items = await getLLMConfig().catch(() => [])
    const map = buildConfigMap(items)
    setConfigs(map)
    const t = items.find((i) => i.provider === 'tavily')
    setTavily(t && t.api_key_masked ? '******' : '')
    return map
  }, [])

  /** 按后端已存配置初始化某供应商草稿（apiKey 输入框留空，保存时留空=保留原 key）。 */
  const seedDraft = (p: LLMProvider, map: LLMConfigMap) => {
    const cfg = map[p]
    const model = cfg?.model ?? ''
    const customMode = !!model && !PROVIDER_MODEL_PRESETS[p].includes(model)
    setDrafts((d) => ({
      ...d,
      [p]: {
        baseUrl: cfg?.baseUrl ?? '',
        apiKey: '',
        model,
        customMode,
        customModel: customMode ? model : '',
      },
    }))
  }

  const setDraftField = (patch: Partial<ProviderDraft>) =>
    setDrafts((d) => ({ ...d, [active]: { ...(d[active] ?? emptyDraft()), ...patch } }))

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // 打开时载入资料与后端 AI 配置
  useEffect(() => {
    if (!open) return
    setTab('profile')
    getProfile().then((u) => {
      if (u) { setProfile(u); setUsername(u.username) }
    }).catch(() => {})
    setProfileMsg(null); setPwdMsg(null); setAiMsg(null)
    reload().then((map) => {
      const p = getActiveProvider()
      setActive(p)
      setApplied(p)
      seedDraft(p, map)
    })
  }, [open, reload])

  if (!open) return null

  const saveProfile = async () => {
    setProfileMsg(null)
    try {
      const u = await updateProfile({ username: username.trim() })
      if (u) setProfile(u)
      setProfileMsg({ kind: 'ok', text: '个人资料已更新' })
    } catch {
      setProfileMsg({ kind: 'err', text: '保存失败，可能用户名已被占用' })
    }
  }

  const submitPwd = async () => {
    setPwdMsg(null)
    if (newPwd.length < 6) { setPwdMsg({ kind: 'err', text: '新密码至少 6 位' }); return }
    if (newPwd !== confirmPwd) { setPwdMsg({ kind: 'err', text: '两次输入的新密码不一致' }); return }
    try {
      await changePassword(oldPwd, newPwd)
      setPwdMsg({ kind: 'ok', text: '密码已修改' })
      setOldPwd(''); setNewPwd(''); setConfirmPwd('')
    } catch {
      setPwdMsg({ kind: 'err', text: '修改失败，请检查旧密码是否正确' })
    }
  }

  /** 切换当前编辑的供应商：只影响该供应商的草稿与保存，不改变「当前应用」。 */
  const switchProvider = (p: LLMProvider) => {
    setActive(p)
    seedDraft(p, configs)
  }

  /** 把某供应商设为当前会话实际使用（聊天按 X-LLM-Provider 注入该配置）。 */
  const applyProvider = (p: LLMProvider) => {
    const cfg = configs[p]
    if (!cfg?.configured) {
      setAiMsg({ kind: 'err', text: `${PROVIDER_LABELS[p]} 尚未配置完整（Base URL / Key / 模型），无法应用` })
      return
    }
    setActiveProvider(p)
    setApplied(p)
    setAiMsg({ kind: 'ok', text: `已应用：${PROVIDER_LABELS[p]}${cfg.model ? ' / ' + cfg.model : ''}` })
  }

  const saveAi = async () => {
    setAiMsg(null)
    const model = draft.customMode
      ? (draft.customModel.trim() || PROVIDER_MODEL_PRESETS[active][0])
      : (draft.model || PROVIDER_MODEL_PRESETS[active][0])
    try {
      await saveLLMConfig({
        provider: active,
        base_url: draft.baseUrl.trim(),
        api_key: draft.apiKey.trim(),
        model,
      })
      setAiMsg({ kind: 'ok', text: `AI 配置已保存（${PROVIDER_LABELS[active]} / ${model}）` })
      const map = await reload()
      seedDraft(active, map)
    } catch {
      setAiMsg({ kind: 'err', text: '保存失败，请重试' })
    }
  }

  const saveTavily = async () => {
    setAiMsg(null)
    try {
      await saveLLMConfig({ provider: 'tavily', base_url: '', api_key: tavily.trim(), model: '' })
      setAiMsg({ kind: 'ok', text: 'Tavily 配置已保存' })
      await reload()
    } catch {
      setAiMsg({ kind: 'err', text: '保存失败，请重试' })
    }
  }

  const removeProvider = async (p: LLMProvider) => {
    if (!window.confirm(`删除 ${PROVIDER_LABELS[p]} 的完整配置（含 API Key）？`)) return
    try {
      await deleteLLMConfig(p)
      setAiMsg({ kind: 'ok', text: `${PROVIDER_LABELS[p]} 配置已删除` })
      const map = await reload()
      seedDraft(p, map)
    } catch {
      setAiMsg({ kind: 'err', text: '删除失败，请重试' })
    }
  }

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: 'profile', label: '个人资料', icon: <IconUser size={15} /> },
    { key: 'ai', label: 'AI 配置', icon: <IconSpark size={15} /> },
  ]

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-lg bg-white rounded-2xl shadow-lift flex flex-col animate-rise overflow-hidden max-h-[85vh]">
        {/* 头部 */}
        <div className="shrink-0 flex items-center gap-3 px-6 pt-5 pb-4 border-b border-line">
          <span className="w-8 h-8 rounded-lg bg-brand-soft text-brand-deep inline-flex items-center justify-center">
            <IconSettings size={16} />
          </span>
          <h3 className="font-display text-lg font-bold text-ink flex-1">设置</h3>
          <button onClick={onClose} className="icon-btn" aria-label="关闭">
            <IconX size={17} />
          </button>
        </div>

        {/* 选项卡 */}
        <div className="shrink-0 px-6 pt-4">
          <div className="flex gap-1 p-1 bg-canvas rounded-xl">
            {tabs.map((t) => (
              <button
                key={t.key}
                onClick={() => setTab(t.key)}
                className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-sm transition-colors ${
                  tab === t.key ? 'bg-white text-brand-deep font-semibold shadow-card' : 'text-ink-mute hover:text-ink'
                }`}
              >
                {t.icon}
                {t.label}
              </button>
            ))}
          </div>
        </div>

        {/* 内容 */}
        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-4">
          {tab === 'profile' && (
            <>
              <div>
                <label className="field-label">用户名</label>
                <input className="field" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" />
              </div>
              <div className="flex items-center gap-2">
                <button onClick={saveProfile} className="btn-primary !py-2">保存资料</button>
                <MsgLine msg={profileMsg} />
              </div>

              <div className="border-t border-line pt-4">
                <p className="text-sm font-medium text-ink mb-3">修改密码</p>
                <div className="space-y-3">
                  <div>
                    <label className="field-label">旧密码</label>
                    <input className="field" type="password" value={oldPwd} onChange={(e) => setOldPwd(e.target.value)} placeholder="输入旧密码" />
                  </div>
                  <div>
                    <label className="field-label">新密码</label>
                    <input className="field" type="password" value={newPwd} onChange={(e) => setNewPwd(e.target.value)} placeholder="至少 6 位" />
                  </div>
                  <div>
                    <label className="field-label">确认新密码</label>
                    <input className="field" type="password" value={confirmPwd} onChange={(e) => setConfirmPwd(e.target.value)} placeholder="再次输入新密码" />
                  </div>
                  <div className="flex items-center gap-2">
                    <button onClick={submitPwd} className="btn-ghost !py-2">修改密码</button>
                    <MsgLine msg={pwdMsg} />
                  </div>
                </div>
              </div>
            </>
          )}

          {tab === 'ai' && (
            <>
              <p className="text-[13px] text-ink-mute leading-relaxed">
                各服务商独立保存 Base URL、API Key 与模型，改一个不影响其他；配置加密存储在后端，仅保存时可修改 Key。
                三项齐全方可聊天（未配置时 Copilot 会提示）。
              </p>
              <div>
                <label className="field-label">服务商类型</label>
                <div className="flex gap-2">
                  {PROVIDERS.map((p) => (
                    <button
                      key={p}
                      type="button"
                      onClick={() => switchProvider(p)}
                      className={`flex-1 py-2 rounded-lg text-sm transition-colors relative ${
                        active === p
                          ? 'bg-brand-soft text-brand-deep font-semibold shadow-card'
                          : 'bg-canvas text-ink-mute hover:text-ink'
                      }`}
                    >
                      {PROVIDER_LABELS[p]}
                      {applied === p && (
                        <span className="absolute -top-1 -right-1 w-2.5 h-2.5 rounded-full bg-brand-deep ring-2 ring-white" title="当前应用" />
                      )}
                    </button>
                  ))}
                </div>
                {/* 当前应用状态 */}
                <div
                  className={`mt-2 rounded-xl px-3 py-2 text-[13px] flex items-center gap-2 ${
                    appliedCfg?.configured ? 'bg-brand-soft text-brand-deep' : 'bg-canvas text-ink-mute'
                  }`}
                >
                  <span className="font-medium">当前应用：{PROVIDER_LABELS[applied]}</span>
                  {appliedCfg?.model && <span className="font-mono">{appliedCfg.model}</span>}
                  <span>
                    {appliedCfg ? (appliedCfg.configured ? '（已配置）' : '（未配置）') : '（未保存）'}
                  </span>
                  {applied !== active && activeCfg?.configured && (
                    <button onClick={() => applyProvider(active)} className="ml-auto underline whitespace-nowrap">
                      应用当前
                    </button>
                  )}
                </div>
              </div>
              <div>
                <label className="field-label">Base URL</label>
                <input
                  className="field"
                  value={draft.baseUrl}
                  onChange={(e) => setDraftField({ baseUrl: e.target.value })}
                  placeholder={PROVIDER_DEFAULT_BASE_URL[active]}
                />
                <p className="text-[12px] text-ink-mute mt-1">仅填服务商根地址（不含 /chat/completions），系统会自动拼接。</p>
              </div>
              <div>
                <label className="field-label">API Key</label>
                <input
                  className="field"
                  type="password"
                  value={draft.apiKey}
                  onChange={(e) => setDraftField({ apiKey: e.target.value })}
                  placeholder={activeCfg?.apiKeyMasked ? `已保存（${activeCfg.apiKeyMasked}），留空保存则保留` : 'sk-...'}
                />
                <p className="text-[12px] text-ink-mute mt-1">支持任意长度 Key，不做截断；明文不会回显。</p>
              </div>
              <div>
                <label className="field-label">模型</label>
                <div className="relative">
                  <select
                    className="field appearance-none pr-10"
                    value={draft.customMode ? '__custom' : (draft.model || PROVIDER_MODEL_PRESETS[active][0])}
                    onChange={(e) => {
                      if (e.target.value === '__custom') { setDraftField({ customMode: true, customModel: '' }) }
                      else { setDraftField({ customMode: false, customModel: '', model: e.target.value }) }
                    }}
                  >
                    {PROVIDER_MODEL_PRESETS[active].map((m) => <option key={m} value={m}>{m}</option>)}
                    <option value="__custom">自定义…</option>
                  </select>
                  <svg
                    className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-ink-mute"
                    width="15"
                    height="15"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.75"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  >
                    <path d="m7 9.5 5 5 5-5" />
                  </svg>
                </div>
              </div>
              {draft.customMode && (
                <div>
                  <label className="field-label">自定义模型名</label>
                  <input className="field" value={draft.customModel} onChange={(e) => setDraftField({ customModel: e.target.value })} placeholder="如 deepseek-v4-flash" />
                </div>
              )}
              <div className="flex items-center gap-2 flex-wrap">
                <button onClick={saveAi} className="btn-primary !py-2">保存</button>
                <button
                  onClick={() => applyProvider(active)}
                  disabled={!activeCfg?.configured}
                  className="btn-ghost !py-2 disabled:opacity-40 disabled:cursor-not-allowed"
                  title={activeCfg?.configured ? `应用 ${PROVIDER_LABELS[active]}` : '配置完整后才能应用'}
                >
                  应用
                </button>
                <button onClick={() => removeProvider(active)} className="btn-ghost !py-2 text-danger">删除</button>
                <MsgLine msg={aiMsg} />
              </div>

              <div className="border-t border-line pt-4">
                <label className="field-label">Tavily API Key（可选，联网搜索）</label>
                <input
                  className="field"
                  type="password"
                  value={tavily}
                  onChange={(e) => setTavily(e.target.value)}
                  placeholder="tvly-...（留空保存保留原 Key；不填则联网搜索不可用）"
                />
                <button onClick={saveTavily} className="btn-ghost !py-2 mt-2">保存 Tavily</button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
