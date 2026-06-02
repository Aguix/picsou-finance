import { api } from '@/lib/api-client'

export interface AdminSecuritySettings {
  allowedOrigins: string[]
  secureCookies: boolean
}

export interface AdminEnableBankingSettings {
  applicationId: string
  keyId: string
  redirectUri: string
}

export interface AdminSettings {
  security: AdminSecuritySettings
  enableBanking: AdminEnableBankingSettings
  integrations: Record<string, boolean>
}

export const adminApi = {
  getSettings: () =>
    api.get<AdminSettings>('/admin/settings').then(r => r.data),

  updateSecurity: (body: AdminSecuritySettings) =>
    api.put<void>('/admin/settings/security', body).then(r => r.data),

  updateEnableBanking: (body: AdminEnableBankingSettings) =>
    api.put<void>('/admin/settings/enablebanking', body).then(r => r.data),

  toggleIntegration: (key: string, enabled: boolean) =>
    api.patch<void>(`/admin/settings/integrations/${key}`, null, { params: { enabled } })
      .then(r => r.data),

  reloadCorsFromEnv: () =>
    api.post<{ allowedOrigins: string[] }>('/admin/settings/cors/reload-from-env')
      .then(r => r.data),
}
