import { useMutation, useQueryClient } from '@tanstack/react-query'
import { authApi } from './api'
import { useAuthStore } from '@/stores/auth-store'
import { useProfileStore } from '@/stores/profile-store'

/**
 * Wipes all per-user client state so nothing leaks across the auth boundary on a
 * shared browser: the persisted impersonation target (`activeMemberId` in
 * localStorage) and the React Query cache, whose data query keys are not scoped
 * by user (`['dashboard', range]`, `['accounts']`, …). Without this a new login
 * would briefly show the previous user's balance/history (staleTime is 60s).
 */
function resetClientState(queryClient: ReturnType<typeof useQueryClient>) {
  useProfileStore.getState().reset()
  queryClient.clear()
}

export function useLogin() {
  const login = useAuthStore(s => s.login)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) =>
      authApi.login(username, password),
    onSuccess: (data) => {
      resetClientState(queryClient)
      login(data)
    },
  })
}

export function useLogout() {
  const logout = useAuthStore(s => s.logout)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => authApi.logout(),
    onSettled: () => {
      logout()
      resetClientState(queryClient)
    },
  })
}
