/** 共享 SVG 图标：统一 1.75 描边 / 圆角线帽，stroke=currentColor 便于着色。 */
interface IconProps {
  className?: string
  strokeWidth?: number
  size?: number
}

const base = (p: IconProps) => ({
  className: p.className,
  width: p.size,
  height: p.size,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: p.strokeWidth ?? 1.75,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
})

export const LogoCloud = ({ size = 30 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 48 48" fill="none">
    <rect x="3" y="3" width="42" height="42" rx="13" fill="url(#lg)" />
    <circle cx="17" cy="27" r="6.5" fill="white" opacity=".9" />
    <circle cx="27.5" cy="18" r="4.5" fill="white" opacity=".55" />
    <rect x="17" y="17" width="7" height="4" rx="2" fill="white" opacity=".5" />
    <rect x="10" y="17" width="7" height="4" rx="2" fill="white" opacity=".35" />
    <circle cx="30" cy="28" r="8" fill="white" opacity=".28" />
    <defs>
      <linearGradient id="lg" x1="6" y1="4" x2="44" y2="46" gradientUnits="userSpaceOnUse">
        <stop stopColor="#1FB8A8" />
        <stop offset="1" stopColor="#0B6F66" />
      </linearGradient>
    </defs>
  </svg>
)

export const IconSend = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M4.5 12 19 5l-3.5 12-4-4.5L6.5 14l-2-2Z" /><path d="M11.5 12.5 19 5" /></svg>
)

export const IconSpark = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M12 3l1.7 4.6L18 9.3l-4.3 1.7L12 15.6l-1.7-4.6L6 9.3l4.3-1.7L12 3Z" /><path d="M19 15l.9 2.4 2.4.9-2.4.9-.9 2.4-.9-2.4-2.4-.9 2.4-.9.9-2.4Z" /></svg>
)

export const IconFolder = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M3.5 6.5A1.5 1.5 0 0 1 5 5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9v8.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-11Z" /></svg>
)

export const IconFile = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M13.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8L13.5 3.5Z" /><path d="M13.5 3.5V8H19" /></svg>
)

export const IconUpload = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M12 15V4m0 0-4 4m4-4 4 4" /><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></svg>
)

export const IconDownload = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M12 4v11m0 0 4-4m-4 4-4-4" /><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></svg>
)

export const IconTrash = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M4.5 7h15M9.5 7V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v2m-7.5 0 .8 12a1.5 1.5 0 0 0 1.5 1.4h5.4a1.5 1.5 0 0 0 1.5-1.4l.8-12" /><path d="M10 11v6M14 11v6" /></svg>
)

export const IconPlus = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M12 5v14M5 12h14" /></svg>
)

export const IconX = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M6 6l12 12M18 6L6 18" /></svg>
)

export const IconLogout = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M9 21H6a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 6 3h3" /><path d="M15.5 16.5 20 12l-4.5-4.5M20 12H9" /></svg>
)

export const IconChat = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M4 6.5A1.5 1.5 0 0 1 5.5 5h13A1.5 1.5 0 0 1 20 6.5v9a1.5 1.5 0 0 1-1.5 1.5h-8L6.5 20v-3H5.5A1.5 1.5 0 0 1 4 15.5v-9Z" /><path d="M8 9.5h8M8 13h5" /></svg>
)

export const IconSearch = (p: IconProps = {}) => (
  <svg {...base(p)}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.5-4.5" /></svg>
)

export const IconCheck = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="m5 12.5 4.5 4.5L19 7" /></svg>
)

export const IconLock = (p: IconProps = {}) => (
  <svg {...base(p)}><rect x="5.5" y="10.5" width="13" height="9" rx="2" /><path d="M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3" /></svg>
)

export const IconUser = (p: IconProps = {}) => (
  <svg {...base(p)}><circle cx="12" cy="8" r="3.5" /><path d="M5 20a7 7 0 0 1 14 0" /></svg>
)

export const IconMail = (p: IconProps = {}) => (
  <svg {...base(p)}><rect x="4" y="6" width="16" height="12" rx="2" /><path d="m4 8 8 5.5L20 8" /></svg>
)

export const IconChevronLeft = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="m14.5 6-6 6 6 6" /></svg>
)

export const IconChevronRight = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="m9.5 6 6 6-6 6" /></svg>
)

export const IconInbox = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M4.5 6.5A1.5 1.5 0 0 1 6 5h12a1.5 1.5 0 0 1 1.5 1.5l-2 10a1.5 1.5 0 0 1-1.5 1.5H7a1.5 1.5 0 0 1-1.5-1.5l-1-10Z" /><path d="M4.5 11h4l1.2 1.6a1.5 1.5 0 0 0 1.2.6h1.4a1.5 1.5 0 0 0 1.2-.6l1.2-1.6h3.1" /></svg>
)

export const IconVideo = (p: IconProps = {}) => (
  <svg {...base(p)}><rect x="3" y="5.5" width="13" height="13" rx="2" /><path d="m22 8-5 4 5 4V8Z" /></svg>
)

export const IconWand = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M14.5 4.5 19.5 9.5 9.8 19.2a2.2 2.2 0 0 1-3-3.2l9.7-9.5Z" /><path d="m4 20 1.5-1.5M17 3.5l-.5-2M21.5 3l1.5.5" /></svg>
)

export const IconPhoto = (p: IconProps = {}) => (
  <svg {...base(p)}><rect x="3.5" y="5" width="17" height="14" rx="2" /><circle cx="9" cy="9.5" r="1.6" /><path d="m4.5 17 5-4.5 3.5 3 3-2.5 3.5 4" /></svg>
)

export const IconFileText = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M13.5 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8L13.5 3.5Z" /><path d="M13.5 3.5V8H19M8.5 12h7M8.5 15.5h5" /></svg>
)

export const IconMore = (p: IconProps = {}) => (
  <svg {...base(p)}><circle cx="5" cy="12" r="1.3" /><circle cx="12" cy="12" r="1.3" /><circle cx="19" cy="12" r="1.3" /></svg>
)

export const IconEye = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" /><circle cx="12" cy="12" r="2.8" /></svg>
)

export const IconEyeOff = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M4 4l16 16" /><path d="M9.5 5.2A9.6 9.6 0 0 1 12 5c6 0 9.5 7 9.5 7a17.6 17.6 0 0 1-2.5 3.3M6.1 6.1A17.5 17.5 0 0 0 2.5 12S6 19 12 19a9.4 9.4 0 0 0 3.4-.6" /><path d="M9.9 9.9a2.8 2.8 0 0 0 4 4" /></svg>
)

export const IconSettings = (p: IconProps = {}) => (
  <svg {...base(p)}><circle cx="12" cy="12" r="3.2" /><path d="M19.4 13.5a7.5 7.5 0 0 0 0-3l2-1.5-2-3.4-2.3.9a7.5 7.5 0 0 0-2.6-1.5L14 2.5h-4l-.5 2.5a7.5 7.5 0 0 0-2.6 1.5l-2.3-.9-2 3.4 2 1.5a7.5 7.5 0 0 0 0 3l-2 1.5 2 3.4 2.3-.9a7.5 7.5 0 0 0 2.6 1.5l.5 2.5h4l.5-2.5a7.5 7.5 0 0 0 2.6-1.5l2.3.9 2-3.4-2-1.5Z" /></svg>
)

export const IconFolderMove = (p: IconProps = {}) => (
  <svg {...base(p)}><path d="M3.5 6.5A1.5 1.5 0 0 1 5 5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9v8.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-11Z" /><path d="m12 13 3.5-3.5L19 13" /><path d="M15.5 9.5V20" /></svg>
)

export const IconShare = (p: IconProps = {}) => (
  <svg {...base(p)}><circle cx="6" cy="12" r="2.5" /><circle cx="17.5" cy="5.5" r="2.5" /><circle cx="17.5" cy="18.5" r="2.5" /><path d="M8.3 10.9 15.2 6.6M8.3 13.1l6.9 4.3" /></svg>
)

export const IconCopy = (p: IconProps = {}) => (
  <svg {...base(p)}><rect x="8.5" y="8.5" width="11" height="11" rx="2" /><path d="M5.5 15.5h-1A1.5 1.5 0 0 1 3 14V5.5A1.5 1.5 0 0 1 4.5 4H13a1.5 1.5 0 0 1 1.5 1.5v1" /></svg>
)