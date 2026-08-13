/** 预览类型判定与文本预览上限。 */

export type PreviewKind = 'image' | 'pdf' | 'text' | 'video' | null

/** 文本预览上限：超过则提示下载而非整文件读入内存。 */
export const TEXT_PREVIEW_MAX = 2 * 1024 * 1024

const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp']
const TEXT_EXT = ['txt', 'md', 'markdown', 'csv', 'json', 'xml', 'yml', 'yaml', 'ini', 'log', 'js', 'ts', 'tsx', 'jsx', 'html', 'css', 'py', 'go', 'java', 'c', 'h', 'cpp', 'cc', 'cxx', 'hpp', 'sh', 'bat', 'sql']
const VIDEO_EXT = ['mp4', 'webm', 'mov', 'mkv']

/** 是否为 Markdown 文件（预览时渲染而非源码）。 */
export function isMarkdown(name: string) {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  return ext === 'md' || ext === 'markdown'
}

export function previewKind(name: string): PreviewKind {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  if (IMAGE_EXT.includes(ext)) return 'image'
  if (ext === 'pdf') return 'pdf'
  if (TEXT_EXT.includes(ext)) return 'text'
  if (VIDEO_EXT.includes(ext)) return 'video'
  return null
}

/** 扩展名取色（用于文件类型图标底色）。 */
export function fileTone(name: string) {
  const ext = name.split('.').pop()?.toLowerCase() ?? ''
  if (IMAGE_EXT.includes(ext)) return 'text-info bg-info/10'
  if (ext === 'pdf') return 'text-danger bg-danger/10'
  if (['xlsx', 'xls', 'csv'].includes(ext)) return 'text-brand-deep bg-brand-soft'
  if (VIDEO_EXT.includes(ext)) return 'text-sand bg-sand/10'
  return 'text-ink-mute bg-canvas'
}
