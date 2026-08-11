/** 文件 MD5 计算工具 — 基于 spark-md5 增量分块，内存友好。 */
import SparkMD5 from 'spark-md5'

const CHUNK_SIZE = 2 * 1024 * 1024

/** 计算文件 MD5（分块读取，onProgress 回调进度 0~1）。 */
export function computeFileMD5(file: File, onProgress?: (p: number) => void): Promise<string> {
  return new Promise((resolve, reject) => {
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    const total = Math.ceil(file.size / CHUNK_SIZE)
    let current = 0

    reader.onerror = () => reject(reader.error)

    const loadNext = () => {
      const start = current * CHUNK_SIZE
      const end = Math.min(start + CHUNK_SIZE, file.size)
      const blob = file.slice(start, end)
      reader.readAsArrayBuffer(blob)
    }

    reader.onload = (e) => {
      if (!e.target || !e.target.result) {
        reject(new Error('read failed'))
        return
      }
      spark.append(e.target.result as ArrayBuffer)
      current += 1
      onProgress?.(Math.min(current / total, 1))
      if (current < total) {
        loadNext()
      } else {
        resolve(spark.end())
      }
    }

    loadNext()
  })
}
