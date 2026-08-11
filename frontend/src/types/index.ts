/** 用户信息 */
export interface User {
  id: number
  username: string
  email: string
  storage_used: number   /** 已用存储（字节） */
  storage_limit: number  /** 存储上限（字节） */
  created_at: string
}

/** 文件元数据 */
export interface FileItem {
  id: number
  name: string
  size: number
  mime_type: string
  md5: string
  folder_id: number | null
  owner_id: number
  created_at: string
  updated_at: string
}

/** 文件夹（含树形子结构和文件列表） */
export interface Folder {
  id: number
  name: string
  parent_id: number | null
  owner_id: number
  children?: Folder[]
  files?: FileItem[]
  created_at: string
}

/** 后端统一响应格式 */
export interface ApiResponse<T = unknown> {
  code: number     /** 0 成功，-1 错误 */
  message: string
  data?: T
}

/** 用户长期记忆 */
export interface MemoryItem {
  fact: string
  created_at: number
}

/** AI 对话会话 */
export interface AgentSession {
  id: string
  title: string
  created_at: number
  message_count: number
  last_preview: string
  /** 会话累计消耗（add_usage 累加） */
  usage_in?: number
  usage_out?: number
  cost_yuan?: number
  model?: string
}

/** 聊天消息（持久化层返回格式） */
export interface ChatMessage {
  role: 'user' | 'human' | 'ai' | 'tool'
  content: string
  /** AI 消息的 token 消耗（流式下为整轮汇总） */
  usage?: {
    input_tokens: number
    output_tokens: number
    total_tokens: number
    model?: string
    provider?: string
    context_window?: number
    cost_yuan?: number
    latency_ms?: number
    truncated?: boolean
    summary_used?: boolean
    dropped_messages?: number
    /** 历史折叠摘要内容（有折叠时存在） */
    summary_text?: string
  }
}

/** SSE 流式事件 */
export interface ChatStreamEvent {
  type: 'text' | 'tool_start' | 'tool_end' | 'meta' | 'error'
  content?: string
  name?: string
  result?: string
  /** type === 'meta' 时的消耗明细（平铺在顶层） */
  model?: string
  provider?: string
  context_window?: number
  input_tokens?: number
  output_tokens?: number
  total_tokens?: number
  cost_yuan?: number
  latency_ms?: number
  /** 上下文预算裁剪信息 */
  truncated?: boolean
  summary_used?: boolean
  dropped_messages?: number
  /** 历史折叠生成的摘要文本（有折叠时存在） */
  summary_text?: string
}
