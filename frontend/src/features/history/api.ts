import { api } from '@/lib/api-client'

export interface AccountPoint {
  total: number
  invested: number
  pnl: number
}

export interface NetWorthPoint {
  date: string
  total: number
  invested: number
  pnl: number
  accounts?: Record<string, AccountPoint>
}

export interface PnlData {
  total: number
  invested: number
  pnl: number
  pnlPercent: number | null
}

export const historyApi = {
  getHistory: (accountIds: number[], months = 12, split = false) =>
    api.get<NetWorthPoint[]>('/history', {
      params: { accountIds: accountIds.join(','), months, split },
    }).then(r => r.data),

  getPnl: (accountIds: number[]) =>
    api.get<PnlData>('/history/pnl', {
      params: { accountIds: accountIds.join(',') },
    }).then(r => r.data),
}
