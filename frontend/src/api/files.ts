/** 文件管理相关 API。 */
import client from './client'
import type { ApiResponse, FileItem } from '../types'

/** 上传文件到指定文件夹（可选）。 */
export async function uploadFile(file: File, folderId?: number) {
  const form = new FormData()
  form.append('file', file)
  if (folderId) form.append('folder_id', String(folderId))
  const res = await client.post<ApiResponse<FileItem>>('/files/upload', form)
  return res.data
}

/** 带进度回调的上传（axios onUploadProgress）。 */
export async function uploadFileWithProgress(
  file: File,
  folderId: number | undefined,
  onProgress?: (percent: number) => void,
) {
  const form = new FormData()
  form.append('file', file)
  if (folderId != null) form.append('folder_id', String(folderId))
  const res = await client.post<ApiResponse<FileItem>>('/files/upload', form, {
    onUploadProgress: (e) => {
      if (e.total && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
  return res.data
}

/** 文件列表分页响应 */
export interface FileListPage {
  files: FileItem[]
  page: number
  total: number
}

/** 获取当前用户的所有文件。 */
export async function listFiles() {
  const res = await client.get<ApiResponse<FileListPage>>('/files')
  return res.data?.data?.files ?? []
}

/** 删除文件。 */
export async function deleteFile(id: number) {
  const res = await client.delete<ApiResponse<null>>(`/files/${id}`)
  return res.data
}

/** 获取文件下载 URL。 */
export function getDownloadUrl(id: number) {
  return `/api/v1/files/${id}/download`
}

/** 带鉴权拉取文件内容（供预览/下载）。 */
export async function getFileBlob(id: number): Promise<Blob> {
  const token = localStorage.getItem('token')
  const resp = await fetch(getDownloadUrl(id), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!resp.ok) throw new Error(`download failed: ${resp.status}`)
  return resp.blob()
}

/** 重命名文件。 */
export async function renameFile(id: number, name: string) {
  const res = await client.put<ApiResponse<null>>(`/files/${id}`, { name })
  return res.data
}

/** 移动文件到文件夹（targetFolderId 为 0 表示移回根目录）。 */
export async function moveFile(id: number, targetFolderId: number) {
  const res = await client.put<ApiResponse<null>>(`/files/${id}/move`, { target_folder_id: targetFolderId })
  return res.data
}

/** 按名称搜索当前用户的文件。 */
export async function searchFiles(q: string) {
  const res = await client.get<ApiResponse<FileListPage>>('/files/search', { params: { q, page: 1, page_size: 50 } })
  return res.data?.data?.files ?? []
}

/** 秒传预检：命中则后端直接建记录返回 instant=true。 */
export async function checksumFile(params: { md5: string; name: string; size: number; folder_id?: number | null }) {
  const res = await client.post<ApiResponse<{ instant: boolean; file?: FileItem }>>('/files/checksum', {
    md5: params.md5,
    name: params.name,
    size: params.size,
    folder_id: params.folder_id ?? null,
  })
  return res.data?.data
}

/** 初始化分块上传。 */
export async function initMultipart(params: {
  name: string
  size: number
  mime_type: string
  folder_id?: number | null
  md5: string
  chunk_size: number
}) {
  const res = await client.post<ApiResponse<{ upload_id: string; chunk_size: number; total_chunks: number }>>(
    '/files/multipart/init',
    {
      name: params.name,
      size: params.size,
      mime_type: params.mime_type,
      folder_id: params.folder_id ?? null,
      md5: params.md5,
      chunk_size: params.chunk_size,
    },
  )
  return res.data?.data
}

/** 上传单个分块。 */
export async function uploadPart(uploadId: string, index: number, blob: Blob) {
  const form = new FormData()
  form.append('index', String(index))
  form.append('data', blob)
  const res = await client.post<ApiResponse<{ received: number[] }>>(`/files/multipart/${uploadId}/parts`, form)
  return res.data?.data
}

/** 合并分块完成上传。 */
export async function completeMultipart(uploadId: string) {
  const res = await client.post<ApiResponse<FileItem>>(`/files/multipart/${uploadId}/complete`)
  return res.data?.data
}

/** 中止并清理分块。 */
export async function abortMultipart(uploadId: string) {
  const res = await client.delete<ApiResponse<null>>(`/files/multipart/${uploadId}`)
  return res.data
}
