/** 通用 Markdown 渲染组件：react-markdown + GFM（表格/任务清单/删除线）。
 *  代码块深色底 + 复制按钮，行内 code 衬底 chips，链接新窗口打开。 */
import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { IconCheck, IconCopy } from './Icons'

interface MarkdownProps {
  children: string
}

/** 复制到剪贴板（降级 document.execCommand）。 */
function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text)
  return new Promise((resolve, reject) => {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy'); resolve() } catch (e) { reject(e) }
    document.body.removeChild(ta)
  })
}

/** 代码块：深色容器 + 复制按钮。 */
function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await copyText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch { /* 复制失败忽略 */ }
  }
  return (
    <div className="group/pre relative my-2 rounded-xl overflow-hidden bg-ink/95 text-[#E8F3F5]">
      <button
        onClick={copy}
        className="absolute top-2 right-2 inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] text-ink-mute bg-white/10 hover:bg-white/20 transition-colors opacity-0 group-hover/pre:opacity-100"
        title="复制代码"
      >
        {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
        {copied ? '已复制' : '复制'}
      </button>
      <pre className="p-3.5 overflow-x-auto text-[12.5px] leading-relaxed font-mono">
        <code>{code}</code>
      </pre>
    </div>
  )
}

export default function Markdown({ children }: MarkdownProps) {
  return (
    <div className="markdown text-[13px] leading-relaxed text-ink break-words min-w-0">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          pre({ children }) {
            // 提取块级代码原文（react-markdown 将块级 code 包在 pre 中）
            const codeEl = Array.isArray(children) ? children[0] : children
            const raw = (codeEl as React.ReactElement)?.props?.children ?? ''
            return <CodeBlock code={String(raw).replace(/\n$/, '')} />
          },
          code({ className, children }) {
            const isBlock = /language-/.test(className ?? '')
            if (isBlock) {
              return <code className="font-mono text-[12.5px]">{children}</code>
            }
            return (
              <code className="px-1.5 py-0.5 rounded-md bg-brand-soft text-brand-deep text-[12px] font-mono">
                {children}
              </code>
            )
          },
          a({ href, children }) {
            return (
              <a href={href} target="_blank" rel="noreferrer noopener" className="text-brand-deep underline underline-offset-2 hover:text-brand">
                {children}
              </a>
            )
          },
          img({ src, alt }) {
            return <img src={src} alt={alt} className="max-w-full rounded-lg my-2" loading="lazy" />
          },
          h1: ({ children }) => <h1 className="text-base font-bold text-ink mt-3 mb-1.5 first:mt-0">{children}</h1>,
          h2: ({ children }) => <h2 className="text-[15px] font-bold text-ink mt-3 mb-1.5 first:mt-0">{children}</h2>,
          h3: ({ children }) => <h3 className="text-sm font-semibold text-ink mt-2.5 mb-1 first:mt-0">{children}</h3>,
          h4: ({ children }) => <h4 className="text-[13px] font-semibold text-ink mt-2 mb-1 first:mt-0">{children}</h4>,
          p: ({ children }) => <p className="my-1.5 first:mt-0 last:mb-0">{children}</p>,
          ul: ({ children }) => <ul className="my-1.5 pl-5 list-disc space-y-0.5 marker:text-brand">{children}</ul>,
          ol: ({ children }) => <ol className="my-1.5 pl-5 list-decimal space-y-0.5 marker:text-brand">{children}</ol>,
          li: ({ children }) => <li className="leading-relaxed">{children}</li>,
          blockquote: ({ children }) => (
            <blockquote className="my-2 border-l-[3px] border-brand/40 bg-brand-pale/60 rounded-r-lg px-3 py-1.5 text-ink-soft">
              {children}
            </blockquote>
          ),
          hr: () => <hr className="my-3 border-line" />,
          table: ({ children }) => (
            <div className="my-2 overflow-x-auto">
              <table className="w-full text-[12.5px] border-collapse rounded-lg overflow-hidden">{children}</table>
            </div>
          ),
          thead: ({ children }) => <thead className="bg-canvas">{children}</thead>,
          th: ({ children }) => <th className="px-2.5 py-1.5 text-left font-semibold text-ink border-b border-line whitespace-nowrap">{children}</th>,
          td: ({ children }) => <td className="px-2.5 py-1.5 border-b border-line text-ink-soft align-top">{children}</td>,
          input({ type }) {
            return (
              <input
                type="checkbox"
                disabled
                checked={type === 'checkbox'}
                className="mr-1.5 -mt-0.5 align-middle accent-brand"
              />
            )
          },
          strong: ({ children }) => <strong className="font-semibold text-ink">{children}</strong>,
          em: ({ children }) => <em className="italic">{children}</em>,
          del: ({ children }) => <del className="line-through text-ink-mute">{children}</del>,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  )
}
