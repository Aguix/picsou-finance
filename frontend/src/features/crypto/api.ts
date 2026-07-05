import { api } from '@/lib/api-client'
import type {
  CoinMappingRequest,
  CoinMappingResponse,
  CryptoImportRequest,
  CryptoImportResult,
  CryptoPreviewResponse,
  CryptoSourceInfo,
  CryptoStatsResponse,
} from '@/types/api'

export const cryptoApi = {
  /** The supported CSV source formats (Crypto.com App/Exchange, Kraken, Binance, ...). */
  sources: () => api.get<CryptoSourceInfo[]>('/crypto/sources').then(r => r.data),

  preview: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    // Override the client's default JSON content-type: passing 'multipart/form-data' makes
    // axios serialize the FormData and append the correct multipart boundary, so the backend
    // accepts the upload instead of rejecting it as application/json (415).
    return api
      .post<CryptoPreviewResponse>('/crypto/preview', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then(r => r.data)
  },

  import: (request: CryptoImportRequest) =>
    api.post<CryptoImportResult>('/crypto/import', request).then(r => r.data),

  /** Pin a ticker the preview couldn't auto-resolve to the coin behind a CoinGecko link. */
  resolveCoin: (request: CoinMappingRequest) =>
    api.post<CoinMappingResponse>('/crypto/coin-mappings', request).then(r => r.data),

  /** Per-account stats — the per-exchange view (rewards detailed by program). */
  stats: (accountId: number) =>
    api.get<CryptoStatsResponse>(`/crypto/accounts/${accountId}/stats`).then(r => r.data),

  // Consolidated stats: every coin pooled across all CRYPTO accounts (imports, exchanges, wallets).
  consolidatedStats: () =>
    api.get<CryptoStatsResponse>('/crypto/stats').then(r => r.data),
}
