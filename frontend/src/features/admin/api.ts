import { api } from '@/lib/api-client'

export interface AdminSecuritySettings {
  allowedOrigins: string[]
  secureCookies: boolean
}

/** The user-editable credential fields (the PUT body). The Key ID is derived
 *  server-side from the Application ID (they are the same value in EB). */
export interface AdminEnableBankingCredentials {
  applicationId: string
  redirectUri: string
}

/** Read shape: credentials plus the server-derived key-present flag. */
export interface AdminEnableBankingSettings extends AdminEnableBankingCredentials {
  privateKeyPresent: boolean
}

export interface EnableBankingKeypairResponse {
  publicKeyPem: string
  regenerated: boolean
}

export interface AdminSettings {
  security: AdminSecuritySettings
  enableBanking: AdminEnableBankingSettings
  integrations: Record<string, boolean>
}

/** One credential session of a price aggregator. Secrets are never sent to the client —
 *  only whether a key/secret is set. */
export interface AggregatorSessionView {
  id: number
  label: string | null
  enabled: boolean
  hasKey: boolean
  hasSecret: boolean
  lastSyncAt: string | null
  createdAt: string
}

export interface AggregatorView {
  key: string
  displayName: string
  enabled: boolean
  sessions: AggregatorSessionView[]
}

/** Create body — raw key/secret; the server encrypts them before persisting. */
export interface CreateAggregatorSessionBody {
  label?: string
  apiKey?: string
  apiSecret?: string
}

export const adminApi = {
  getSettings: () =>
    api.get<AdminSettings>('/admin/settings').then(r => r.data),

  updateSecurity: (body: AdminSecuritySettings) =>
    api.put<void>('/admin/settings/security', body).then(r => r.data),

  updateEnableBanking: (body: AdminEnableBankingCredentials) =>
    api.put<void>('/admin/settings/enablebanking', body).then(r => r.data),

  generateEnableBankingKeyPair: () =>
    api.post<EnableBankingKeypairResponse>('/admin/settings/enablebanking/keypair')
      .then(r => r.data),

  importEnableBankingPrivateKey: (privatePem: string) =>
    api.post<EnableBankingKeypairResponse>('/admin/settings/enablebanking/keypair/import', { privatePem })
      .then(r => r.data),

  toggleIntegration: (key: string, enabled: boolean) =>
    api.patch<void>(`/admin/settings/integrations/${key}`, null, { params: { enabled } })
      .then(r => r.data),

  reloadCorsFromEnv: () =>
    api.post<{ allowedOrigins: string[] }>('/admin/settings/cors/reload-from-env')
      .then(r => r.data),

  getAggregators: () =>
    api.get<AggregatorView[]>('/admin/aggregators').then(r => r.data),

  toggleAggregator: (key: string, enabled: boolean) =>
    api.patch<void>(`/admin/aggregators/${key}`, null, { params: { enabled } }).then(r => r.data),

  createAggregatorSession: (key: string, body: CreateAggregatorSessionBody) =>
    api.post<AggregatorSessionView>(`/admin/aggregators/${key}/sessions`, body).then(r => r.data),

  toggleAggregatorSession: (id: number, enabled: boolean) =>
    api.patch<void>(`/admin/aggregators/sessions/${id}`, null, { params: { enabled } }).then(r => r.data),

  deleteAggregatorSession: (id: number) =>
    api.delete<void>(`/admin/aggregators/sessions/${id}`).then(r => r.data),
}
