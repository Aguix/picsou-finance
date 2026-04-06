import { create } from 'zustand'

interface AuthState {
  username: string | null
  isAuthenticated: boolean
  login: (username: string) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  username: sessionStorage.getItem('picsou_user'),
  isAuthenticated: !!sessionStorage.getItem('picsou_user'),
  login: (username) => {
    sessionStorage.setItem('picsou_user', username)
    set({ username, isAuthenticated: true })
  },
  logout: () => {
    sessionStorage.removeItem('picsou_user')
    set({ username: null, isAuthenticated: false })
  },
}))
