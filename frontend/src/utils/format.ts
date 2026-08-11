/** 字节数格式化为可读字符串（B / KB / MB / GB）。 */
export function formatBytes(bytes: number): string {
  const b = Number(bytes) || 0
  if (b < 1024) return b + ' B'
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB'
  return (b / 1073741824).toFixed(2) + ' GB'
}
