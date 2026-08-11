/** 应用根组件，定义路由和未登录时的保护跳转。 */
import { Routes, Route, Navigate } from 'react-router-dom'
import Login from './pages/Login'
import Register from './pages/Register'
import Dashboard from './pages/Dashboard'
import ShareView from './pages/ShareView'

/** 检查本地存储是否有 JWT 令牌。 */
const token = () => localStorage.getItem('token')

/** 路由守卫：未登录时重定向到 /login。 */
function ProtectedRoute({ children }: { children: React.ReactNode }) {
  if (!token()) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      {/* 公开分享落地页（无需登录） */}
      <Route path="/s/:token" element={<ShareView />} />
      <Route
        path="/*"
        element={
          <ProtectedRoute>
            <Dashboard />
          </ProtectedRoute>
        }
      />
    </Routes>
  )
}
