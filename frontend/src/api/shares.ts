/** 文件分享相关 API。 */
import client from './client'
import type { ApiResponse, FileItem } from '../types'

export interface ShareCreateResult {
  id: number
  token: string
  url: string
}

/**
 * 后端源（公开分享走后端直连，避免 vite `/s` 代理前缀误匹配 `/src/*`）。
 * 开发默认 localhost:8080；可用 `VITE_BACKEND_ORIGIN` 覆盖（生产同源可指向当前站点）。
 */
const backendOrigin = () =>
  (import.meta as { env?: Record<string, string | undefined> }).env?.VITE_BACKEND_ORIGIN || 'http://localhost:8080'

/** 创建分享链接。 */
export async function createShare(fileId: number, expireHours?: number) {
  const res = await client.post<ApiResponse<ShareCreateResult>>('/shares', { file_id: fileId, expire_hours: expireHours })
  return res.data?.data
}

/** 取消分享。 */
export async function deleteShare(id: number) {
  const res = await client.delete<ApiResponse<null>>(`/shares/${id}`)
  return res.data
}

/** 公开访问分享元数据（无需登录）。 */
export async function getShare(token: string): Promise<FileItem | null> {
  const resp = await fetch(`${backendOrigin()}/s/${token}`)
  if (!resp.ok) return null
  const json = await resp.json() as ApiResponse<FileItem>
  return json.data ?? null
}

/** 公开下载分享文件的 URL。 */
export function getShareDownloadUrl(token: string) {
  return `${backendOrigin()}/s/${token}/download`
}
