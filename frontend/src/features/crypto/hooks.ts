import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cryptoApi } from './api'
import type { CoinMappingRequest, CryptoImportRequest } from '@/types/api'

export const cryptoKeys = {
  all: ['crypto'] as const,
  sources: () => [...cryptoKeys.all, 'sources'] as const,
  stats: (accountId: number) => [...cryptoKeys.all, 'stats', accountId] as const,
  consolidated: () => [...cryptoKeys.all, 'stats', 'consolidated'] as const,
  mappings: () => [...cryptoKeys.all, 'mappings'] as const,
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

/** All known ticker → CoinGecko mappings, for the management UI. */
export function useCoinMappings(enabled = true) {
  return useQuery({
    queryKey: cryptoKeys.mappings(),
    queryFn: () => cryptoApi.coinMappings(),
    enabled,
    staleTime: 60_000,
  })
}

/**
 * Pin an ambiguous ticker to a coin via its CoinGecko link — used to resolve a new ticker at
 * import time and to correct a wrong mapping. A correction changes the coin's prices, so every
 * crypto/value query is refetched.
 */
export function useResolveCoin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CoinMappingRequest) => cryptoApi.resolveCoin(request),
    onSuccess: () => invalidateAfterMappingChange(queryClient),
  })
}

/** Mark a delisted ticker as worthless — CoinGecko can't price it, so it's pinned to zero. */
export function useMarkCoinWorthless() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticker: string) => cryptoApi.markCoinWorthless(ticker),
    onSuccess: () => invalidateAfterMappingChange(queryClient),
  })
}

/** Forget a mapping made by mistake — the ticker goes back to unresolved (and unpriced). */
export function useDeleteCoinMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticker: string) => cryptoApi.deleteCoinMapping(ticker),
    onSuccess: () => invalidateAfterMappingChange(queryClient),
  })
}

/** A mapping change re-prices the coin (history included) — refetch everything value-related. */
function invalidateAfterMappingChange(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: cryptoKeys.all })
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
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
