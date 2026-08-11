/** 用户 LLM 配置 API（按供应商独立，后端加密存储；apiKey 仅返回脱敏值）。 */
import client from './client'
import type { ApiResponse } from '../types'

export interface LLMConfigItem {
  provider: string
  base_url: string
  api_key_masked: string
  model: string
  configured: boolean
  updated_at: string
}

export async function getLLMConfig(): Promise<LLMConfigItem[]> {
  const res = await client.get<ApiResponse<{ configs: LLMConfigItem[] }>>('/llm-config')
  return res.data.data?.configs ?? []
}

/** 保存单个供应商配置；api_key 为空表示保留已有 key（改 base_url/model 不清 key）。 */
export async function saveLLMConfig(cfg: { provider: string; base_url: string; api_key?: string; model: string }) {
  const res = await client.put<ApiResponse<null>>('/llm-config', cfg)
  return res.data
}

export async function deleteLLMConfig(provider: string) {
  const res = await client.delete<ApiResponse<null>>(`/llm-config/${encodeURIComponent(provider)}`)
  return res.data
}
