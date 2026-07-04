import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cryptoApi } from './api'
import type { CryptoImportRequest } from '@/types/api'

export const cryptoKeys = {
  all: ['crypto'] as const,
  sources: () => [...cryptoKeys.all, 'sources'] as const,
  stats: (accountId: number) => [...cryptoKeys.all, 'stats', accountId] as const,
  consolidated: () => [...cryptoKeys.all, 'stats', 'consolidated'] as const,
}

/** The supported CSV source formats — static per backend build, cached aggressively. */
export function useCryptoSources() {
  return useQuery({
    queryKey: cryptoKeys.sources(),
    queryFn: () => cryptoApi.sources(),
    staleTime: Infinity,
  })
}

export function usePreviewCryptoCsv() {
  return useMutation({
    mutationFn: (file: File) => cryptoApi.preview(file),
  })
}

export function useImportCrypto() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CryptoImportRequest) => cryptoApi.import(request),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      queryClient.invalidateQueries({ queryKey: cryptoKeys.stats(result.accountId) })
      queryClient.invalidateQueries({ queryKey: cryptoKeys.consolidated() })
    },
  })
}

export function useCryptoStats(accountId: number, enabled = true) {
  return useQuery({
    queryKey: cryptoKeys.stats(accountId),
    queryFn: () => cryptoApi.stats(accountId),
    enabled: enabled && Number.isFinite(accountId),
    staleTime: 60_000,
  })
}

/** Consolidated stats across all of the member's CRYPTO accounts (all platforms + wallets). */
export function useConsolidatedCryptoStats() {
  return useQuery({
    queryKey: cryptoKeys.consolidated(),
    queryFn: () => cryptoApi.consolidatedStats(),
    staleTime: 60_000,
  })
}
