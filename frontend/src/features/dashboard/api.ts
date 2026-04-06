import { api } from '@/lib/api-client'
import type { DashboardData } from '@/types/api'

export const dashboardApi = {
  get: (range?: string) =>
    api.get<DashboardData>('/dashboard', { params: range ? { range } : {} }).then(r => r.data),
}
