/** 用户认证相关 API。 */
import client from './client'
import type { ApiResponse, User } from '../types'

/** 登录，返回 JWT 令牌和用户信息。 */
export async function login(username: string, password: string) {
  const res = await client.post<ApiResponse<{ token: string; user: User }>>('/auth/login', { username, password })
  return res.data
}

/** 注册新用户。 */
export async function register(username: string, email: string, password: string) {
  const res = await client.post<ApiResponse<User>>('/auth/register', { username, email, password })
  return res.data
}

/** 获取当前用户信息（含存储配额）。 */
export async function getProfile() {
  const res = await client.get<ApiResponse<User>>('/auth/profile')
  return res.data?.data
}

/** 更新用户名。 */
export async function updateProfile(data: { username?: string }) {
  const res = await client.put<ApiResponse<User>>('/auth/profile', data)
  return res.data?.data
}

/** 修改密码（旧密码校验）。 */
export async function changePassword(oldPassword: string, newPassword: string) {
  const res = await client.put<ApiResponse<null>>('/auth/password', {
    old_password: oldPassword,
    new_password: newPassword,
  })
  return res.data
}

/** 登出：将当前 token 加入黑名单使其立即失效。 */
export async function logout() {
  const res = await client.post<ApiResponse<null>>('/auth/logout')
  return res.data
}
