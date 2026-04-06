import { useMutation } from '@tanstack/react-query'
import { authApi } from './api'
import { useAuthStore } from '@/stores/auth-store'

export function useLogin() {
  const login = useAuthStore(s => s.login)
  return useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) =>
      authApi.login(username, password),
    onSuccess: (data) => login(data.username),
  })
}

export function useLogout() {
  const logout = useAuthStore(s => s.logout)
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => logout(),
  })
}
