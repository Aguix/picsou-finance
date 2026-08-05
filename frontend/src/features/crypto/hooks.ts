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

  function invalidate(accountId: number) {
    queryClient.invalidateQueries({ queryKey: ['accounts'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    queryClient.invalidateQueries({ queryKey: cryptoKeys.stats(accountId) })
    queryClient.invalidateQueries({ queryKey: cryptoKeys.consolidated() })
  }

  return useMutation({
    mutationFn: (request: CryptoImportRequest) => cryptoApi.import(request),
    // The import returns as soon as the account, transactions and (unpriced) holdings are persisted:
    // a first invalidation shows the positions immediately. Prices are backfilled in the background,
    // so we long-poll the /pricing endpoint (one request, resolves when the job lands) and invalidate
    // again to reveal the valued holdings — without blocking the import response on the network.
    onSuccess: (result) => {
      invalidate(result.accountId)
      cryptoApi
        .awaitPricing(result.accountId)
        .catch(() => undefined) // a timeout/failure just means "refetch now"
        .then(() => invalidate(result.accountId))
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
