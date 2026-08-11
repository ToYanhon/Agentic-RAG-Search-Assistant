/** AI 服务设置：按供应商独立，配置由后端加密存储；前端仅维护「当前供应商」选择。
 *  请求头只发 X-LLM-Provider，Base URL / API Key / 模型由后端在代理时按存储配置注入。 */

import type { LLMConfigItem } from '../api/llmConfig'

export type LLMProvider = 'openai' | 'anthropic' | 'kimi' | 'zhipu'

export interface ProviderConfig {
  baseUrl: string
  /** 后端脱敏展示值（如 ******），空串表示未保存 key */
  apiKeyMasked: string
  model: string
  /** base_url / api_key / model 三要素齐全 */
  configured: boolean
}

export type LLMConfigMap = Record<string, ProviderConfig>

/** 供应商展示名（自定义/未知供应商回退用原名）。 */
export const PROVIDER_LABELS: Record<string, string> = {
  openai: 'OpenAI 兼容',
  anthropic: 'Anthropic (Claude)',
  kimi: 'Kimi (Moonshot)',
  zhipu: '智谱 (GLM)',
  tavily: 'Tavily',
}

/** 各类型默认 Base URL（切换类型时自动填充）。 */
export const PROVIDER_DEFAULT_BASE_URL: Record<LLMProvider, string> = {
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com',
  kimi: 'https://api.moonshot.cn/v1',
  zhipu: 'https://open.bigmodel.cn/api/paas/v4',
}

/** 各类型推荐模型（切换类型时联动可选）。 */
export const PROVIDER_MODEL_PRESETS: Record<LLMProvider, string[]> = {
  openai: ['deepseek-chat', 'deepseek-v4-flash', 'deepseek-reasoner', 'gpt-4o-mini', 'gpt-4o'],
  anthropic: ['claude-3-5-sonnet-20241022', 'claude-3-7-sonnet-20250219', 'claude-3-5-haiku-20241022'],
  kimi: ['kimi-k2-0711-preview', 'kimi-latest', 'moonshot-v1-32k'],
  zhipu: ['glm-4-flash', 'glm-4-plus', 'glm-4-air', 'glm-4-long'],
}

const ACTIVE_KEY = 'settings:ai:active'

function isProvider(v: unknown): v is LLMProvider {
  return v === 'openai' || v === 'anthropic' || v === 'kimi' || v === 'zhipu'
}

/** 当前使用的供应商（仅此选择存 localStorage，不含任何密钥）。 */
export function getActiveProvider(): LLMProvider {
  try {
    const v = localStorage.getItem(ACTIVE_KEY)
    return isProvider(v) ? v : 'openai'
  } catch {
    return 'openai'
  }
}

export function setActiveProvider(p: LLMProvider) {
  try {
    localStorage.setItem(ACTIVE_KEY, p)
  } catch {
    /* ignore */
  }
}

/**
 * 生成发送给 Agent 的请求头。仅携带当前供应商：
 * Base URL / API Key / 模型由后端按其存储配置注入，前端不再携带密钥。
 */
export function aiRequestHeaders(): Record<string, string> {
  return { 'X-LLM-Provider': getActiveProvider() }
}

/** 将后端配置列表转为按 provider 的 map。 */
export function buildConfigMap(items: LLMConfigItem[]): LLMConfigMap {
  const m: LLMConfigMap = {}
  for (const it of items) {
    m[it.provider] = {
      baseUrl: it.base_url,
      apiKeyMasked: it.api_key_masked,
      model: it.model,
      configured: it.configured,
    }
  }
  return m
}
