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

  /** All known ticker → CoinGecko mappings, for the management UI. */
  coinMappings: () =>
    api.get<CoinMappingResponse[]>('/crypto/coin-mappings').then(r => r.data),

  /**
   * Pin a ticker to the coin behind a CoinGecko link — resolves a ticker the preview couldn't
   * auto-resolve, or corrects a wrong mapping (the backend then refetches its price history).
   */
  resolveCoin: (request: CoinMappingRequest) =>
    api.post<CoinMappingResponse>('/crypto/coin-mappings', request).then(r => r.data),

  /** Mark a delisted ticker CoinGecko can't price as worthless — pinned to a value of zero. */
  markCoinWorthless: (ticker: string) =>
    api
      .post<CoinMappingResponse>(`/crypto/coin-mappings/${encodeURIComponent(ticker)}/worthless`)
      .then(r => r.data),

  /** Forget a mapping made by mistake — the ticker goes back to unresolved. */
  deleteCoinMapping: (ticker: string) =>
    api.delete<void>(`/crypto/coin-mappings/${encodeURIComponent(ticker)}`).then(() => undefined),

  /** Per-account stats — the per-exchange view (rewards detailed by program). */
  stats: (accountId: number) =>
    api.get<CryptoStatsResponse>(`/crypto/accounts/${accountId}/stats`).then(r => r.data),

  // Consolidated stats: every coin pooled across all CRYPTO accounts (imports, exchanges, wallets).
  consolidatedStats: () =>
    api.get<CryptoStatsResponse>('/crypto/stats').then(r => r.data),
}
