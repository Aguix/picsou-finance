import { api } from '@/lib/api-client'
import type { AssetCandidatesResponse, AssetMappingRequest, AssetResponse } from '@/types/api'

/**
 * Standing mapping/verification of the global `financial_asset` registry — the always-available
 * counterpart to the crypto import preview. Reached per-symbol from the holding detail; the registry
 * is member-agnostic so these routes are not account-scoped.
 */
export const assetsApi = {
  candidates: (symbol: string) =>
    api.get<AssetCandidatesResponse>(`/assets/${encodeURIComponent(symbol)}/candidates`).then(r => r.data),
  map: (symbol: string, data: AssetMappingRequest) =>
    api.put<AssetResponse>(`/assets/${encodeURIComponent(symbol)}/mapping`, data).then(r => r.data),
  forget: (symbol: string) =>
    api.delete(`/assets/${encodeURIComponent(symbol)}`),
}
