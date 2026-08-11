/** 拖拽载荷：文件树行拖动传输的类型 + id。 */
export interface DnDPayload {
  kind: 'file' | 'folder'
  id: number
}

/** 用 text/plain 承载 JSON，保证跨浏览器 getData 可用。 */
export const DND_MIME = 'text/plain'

export function encodeDnD(p: DnDPayload): string {
  return JSON.stringify(p)
}

export function decodeDnD(raw: string | null): DnDPayload | null {
  if (!raw) return null
  try {
    const p = JSON.parse(raw) as DnDPayload
    if ((p.kind === 'file' || p.kind === 'folder') && typeof p.id === 'number') return p
  } catch {
    /* 非本应用载荷，忽略 */
  }
  return null
}
