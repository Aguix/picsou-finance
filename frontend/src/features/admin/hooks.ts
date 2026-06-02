import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { adminApi } from './api'
import type { AdminSecuritySettings, AdminEnableBankingSettings } from './api'

export const adminKeys = {
  all: ['admin'] as const,
  settings: () => [...adminKeys.all, 'settings'] as const,
}

export function useAdminSettings() {
  return useQuery({
    queryKey: adminKeys.settings(),
    queryFn: adminApi.getSettings,
    staleTime: 60_000,
  })
}

export function useUpdateSecurity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminSecuritySettings) => adminApi.updateSecurity(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useUpdateEnableBanking() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminEnableBankingSettings) => adminApi.updateEnableBanking(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useReloadCorsFromEnv() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => adminApi.reloadCorsFromEnv(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useToggleIntegration() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) =>
      adminApi.toggleIntegration(key, enabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}
