import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { assetsApi } from './api'
import type { AssetMappingRequest } from '@/types/api'

/** The whole asset registry, for the management table. */
export function useAssets(enabled = true) {
  return useQuery({
    queryKey: ['assets', 'list'],
    queryFn: assetsApi.list,
    enabled,
    staleTime: 60_000,
  })
}

/**
 * CoinGecko candidates for a symbol (standing mapping editor). Lazy — only fetched when `enabled`,
 * so opening a holding detail doesn't fire a CoinGecko `/search` until the operator actually edits
 * the mapping (the free tier rate-limits hard). Never retried: a miss is a legitimate empty result,
 * not a transient error to hammer.
 */
export function useAssetCandidates(symbol: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ['assets', symbol, 'candidates'],
    queryFn: () => assetsApi.candidates(symbol!),
    enabled: enabled && !!symbol,
    retry: false,
    staleTime: 5 * 60_000,
  })
}

// A mapping change re-values holdings and (on a coin-id change) purges/refetches price history, so
// invalidate everything derived from prices/holdings: accounts (holdings, portfolio, detail),
// dashboard, and the symbol's own candidate/price caches.
function invalidateAfterMapping(queryClient: ReturnType<typeof useQueryClient>, symbol: string) {
  queryClient.invalidateQueries({ queryKey: ['accounts'] })
  queryClient.invalidateQueries({ queryKey: ['dashboard'] })
  queryClient.invalidateQueries({ queryKey: ['assets'] })   // registry list + per-symbol candidates
  queryClient.invalidateQueries({ queryKey: ['prices', symbol] })
}

export function useApplyAssetMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ symbol, data }: { symbol: string; data: AssetMappingRequest }) =>
      assetsApi.map(symbol, data),
    onSuccess: (_res, { symbol }) => invalidateAfterMapping(queryClient, symbol),
  })
}

export function useForgetAssetMapping() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (symbol: string) => assetsApi.forget(symbol),
    onSuccess: (_res, symbol) => invalidateAfterMapping(queryClient, symbol),
  })
}
