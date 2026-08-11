/** 文件夹管理相关 API。 */
import client from './client'
import type { ApiResponse, Folder } from '../types'

/** 创建文件夹。 */
export async function createFolder(name: string, parentId?: number) {
  const res = await client.post<ApiResponse<Folder>>('/folders', { name, parent_id: parentId })
  return res.data
}

/** 获取用户根文件夹列表。 */
export async function getRootFolders() {
  const res = await client.get<ApiResponse<Folder[]>>('/folders/root')
  return res.data
}

/** 获取文件夹的树形内容（含子文件夹和文件）。 */
export async function getFolderTree(id: number) {
  const res = await client.get<ApiResponse<Folder>>(`/folders/${id}`)
  return res.data
}

/** 重命名文件夹。 */
export async function renameFolder(id: number, name: string) {
  const res = await client.put<ApiResponse<null>>(`/folders/${id}`, { name })
  return res.data
}

/** 移动文件夹（targetParentId 为 0 表示移回根目录）。 */
export async function moveFolder(id: number, targetParentId: number) {
  const res = await client.put<ApiResponse<null>>(`/folders/${id}/move`, { target_parent_id: targetParentId })
  return res.data
}

/** 删除文件夹（级联删除）。 */
export async function deleteFolder(id: number) {
  const res = await client.delete<ApiResponse<null>>(`/folders/${id}`)
  return res.data
}
