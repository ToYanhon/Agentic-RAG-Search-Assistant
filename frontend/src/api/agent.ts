/** AI Agent 相关 API。 */
import client from './client'
import type { AgentSession, ApiResponse, ChatMessage, ChatStreamEvent, MemoryItem } from '../types'
import { aiRequestHeaders } from '../utils/settings'

/** 建立文件语义索引（embed）。长超时覆盖 30s 实例默认。agent 返回裸结构。 */
export async function indexFile(fileId: number, filename?: string) {
  const res = await client.post<{ status: string; chunks?: number; reason?: string }>(
    `/agent/index/${fileId}`,
    null,
    { params: filename ? { filename } : {}, headers: aiRequestHeaders(), timeout: 120000 },
  )
  return res.data
}

/** 生成文件摘要（LLM）。需用户已配置 AI 设置（X-LLM 头透传），agent 返回裸结构。 */
export async function summarizeFile(fileId: number, filename?: string) {
  const res = await client.post<{ summary: string }>(
    `/agent/summary/${fileId}`,
    null,
    { params: filename ? { filename } : {}, headers: aiRequestHeaders(), timeout: 60000 },
  )
  return res.data
}

/** 递归建立文件夹下所有文件的语义索引（embed）。agent 返回裸结构。 */
export async function indexFolder(folderId: number) {
  const res = await client.post<{ status: string; total: number; indexed: number; skipped: number; failed: number; truncated: number }>(
    `/agent/index/folder/${folderId}`,
    null,
    { headers: aiRequestHeaders(), timeout: 120000 },
  )
  return res.data
}

/** 批量查询文件索引状态，返回 {id: true|false}（裸结构）。 */
export async function getIndexStatus(fileIds: number[]) {
  const res = await client.post<Record<string, boolean>>('/agent/index/status', { files: fileIds })
  return res.data
}

/** 取消单文件索引。 */
export async function unindexFile(fileId: number) {
  const res = await client.delete<{ status: string }>(`/agent/index/${fileId}`)
  return res.data
}

/** 查询文件夹下全部文件索引状态。 */
export async function getFolderIndexStatus(folderId: number) {
  const res = await client.post<{ status: string; total: number; indexed: number; all_indexed: boolean }>(
    `/agent/index/folder/${folderId}/status`,
  )
  return res.data
}

/** 递归取消文件夹下所有文件索引。 */
export async function unindexFolder(folderId: number) {
  const res = await client.delete<{ status: string; removed: number }>(`/agent/index/folder/${folderId}`)
  return res.data
}

/** 列出当前用户的对话会话（分页）。 */
export async function listSessions(page = 1, pageSize = 50) {
  const res = await client.get<ApiResponse<{ items: AgentSession[]; total: number; page: number; page_size: number }>>(
    '/agent/chat/sessions',
    { params: { page, page_size: pageSize } },
  )
  return res.data
}

/** 仅取会话列表数组（兼容旧调用）。 */
export async function listSessionItems(page = 1, pageSize = 50) {
  const res = await listSessions(page, pageSize)
  return res.data?.items ?? []
}

/** 创建新会话。 */
export async function createSession(title: string) {
  const res = await client.post<ApiResponse<{ id: string }>>('/agent/chat/sessions', { title })
  return res.data
}

/** 删除会话。 */
export async function deleteSession(id: string) {
  const res = await client.delete<ApiResponse<string>>(`/agent/chat/sessions/${id}`)
  return res.data
}

/** 重命名会话。 */
export async function renameSession(id: string, title: string) {
  const res = await client.put<ApiResponse<string>>(`/agent/chat/sessions/${id}`, { title })
  return res.data
}

/** 直接向会话追加消息（不触发 LLM 工作流），用于写入预生成的摘要等。 */
export async function appendSessionMessages(sessionId: string, messages: { role: string; content: string }[]) {
  const res = await client.post<ApiResponse<string>>(
    `/agent/chat/sessions/${sessionId}/messages/append`,
    { messages },
  )
  return res.data
}

/** 加载会话历史消息。 */
export async function getMessages(id: string) {
  const res = await client.get<ApiResponse<ChatMessage[]>>(`/agent/chat/sessions/${id}/messages`)
  return res.data
}

/** 列出当前用户长期记忆。 */
export async function listMemories() {
  const res = await client.get<ApiResponse<MemoryItem[]>>('/agent/memory')
  return res.data
}

/** 按关键词删除长期记忆。 */
export async function deleteMemory(keyword: string) {
  const res = await client.delete<ApiResponse<{ removed: number }>>('/agent/memory', {
    params: { keyword },
  })
  return res.data
}

/**
 * 发送消息（SSE 流式）。
 * fetch 手动处理 text/event-stream，逐事件产出；signal 用于切换/关闭会话时中止流（F2）。
 */
export async function* sendMessageStream(
  sessionId: string,
  message: string,
  signal?: AbortSignal,
): AsyncGenerator<ChatStreamEvent> {
  const token = localStorage.getItem('token')
  const resp = await fetch(`/api/v1/agent/chat/sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      // 用户 AI 配置（baseUrl/apiKey/model）→ Agent 请求级覆盖
      ...aiRequestHeaders(),
    },
    body: JSON.stringify({ message }),
    signal,
  })
  if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`)

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const raw = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      for (const line of raw.split('\n')) {
        if (!line.startsWith('data: ')) continue
        const payload = line.slice(6)
        if (payload === '[DONE]') return
        try {
          yield JSON.parse(payload) as ChatStreamEvent
        } catch {
          /* 忽略无法解析的帧 */
        }
      }
    }
  }
}
