import { beforeEach, describe, expect, it, vi } from 'vitest'

class MemoryStorage {
  constructor() { this.map = new Map() }
  getItem(key) { return this.map.has(key) ? this.map.get(key) : null }
  setItem(key, value) { this.map.set(key, String(value)) }
  removeItem(key) { this.map.delete(key) }
  clear() { this.map.clear() }
}

describe('auth store', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage())
    vi.resetModules()
  })

  it('persists login session and checks permissions', async () => {
    const { auth } = await import('./auth')

    auth.setSession({
      token: 'jwt-token',
      id: 7,
      username: 'qinghuan',
      displayName: '清欢',
      roleId: 2,
      roleCode: 'USER',
      roleName: '普通用户',
      permissions: ['task:self:view']
    })

    expect(auth.isLoggedIn.value).toBe(true)
    expect(auth.user.value.displayName).toBe('清欢')
    expect(auth.hasPermission('task:self:view')).toBe(true)
    expect(auth.hasPermission('user:manage')).toBe(false)
  })

  it('clears persisted session', async () => {
    const { auth } = await import('./auth')
    auth.setSession({ token: 'token', permissions: [] })

    auth.clear()

    expect(auth.isLoggedIn.value).toBe(false)
    expect(localStorage.getItem('it-workbench-session')).toBeNull()
  })
})
