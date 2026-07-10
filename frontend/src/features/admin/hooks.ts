import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { adminApi } from './api'
import type {
  AdminSecuritySettings,
  AdminEnableBankingCredentials,
  CreateAggregatorSessionBody,
} from './api'

export const adminKeys = {
  all: ['admin'] as const,
  settings: () => [...adminKeys.all, 'settings'] as const,
  aggregators: () => [...adminKeys.all, 'aggregators'] as const,
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
    mutationFn: (body: AdminEnableBankingCredentials) => adminApi.updateEnableBanking(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useGenerateEnableBankingKeyPair() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => adminApi.generateEnableBankingKeyPair(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.settings() }),
  })
}

export function useImportEnableBankingPrivateKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (privatePem: string) => adminApi.importEnableBankingPrivateKey(privatePem),
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

export function useAggregators() {
  return useQuery({
    queryKey: adminKeys.aggregators(),
    queryFn: adminApi.getAggregators,
    staleTime: 60_000,
  })
}

export function useToggleAggregator() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, enabled }: { key: string; enabled: boolean }) =>
      adminApi.toggleAggregator(key, enabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.aggregators() }),
  })
}

export function useCreateAggregatorSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, body }: { key: string; body: CreateAggregatorSessionBody }) =>
      adminApi.createAggregatorSession(key, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.aggregators() }),
  })
}

export function useToggleAggregatorSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
      adminApi.toggleAggregatorSession(id, enabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.aggregators() }),
  })
}

export function useDeleteAggregatorSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => adminApi.deleteAggregatorSession(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.aggregators() }),
  })
}
