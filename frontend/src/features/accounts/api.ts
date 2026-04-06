import { api } from '@/lib/api-client'
import type { Account, AccountRequest, BalanceSnapshot, HoldingResponse, Transaction } from '@/types/api'

export const accountsApi = {
  list: () => api.get<Account[]>('/accounts').then(r => r.data),
  get: (id: number) => api.get<Account>(`/accounts/${id}`).then(r => r.data),
  create: (data: AccountRequest) => api.post<Account>('/accounts', data).then(r => r.data),
  update: (id: number, data: AccountRequest) => api.put<Account>(`/accounts/${id}`, data).then(r => r.data),
  delete: (id: number) => api.delete(`/accounts/${id}`),
  history: (id: number, from?: string, to?: string) =>
    api.get<BalanceSnapshot[]>(`/accounts/${id}/history`, { params: { from, to } }).then(r => r.data),
  holdings: (id: number) =>
    api.get<HoldingResponse[]>(`/accounts/${id}/holdings`).then(r => r.data),
  transactions: (id: number) =>
    api.get<Transaction[]>(`/accounts/${id}/transactions`).then(r => r.data),
  prices: (tickers: string[]) =>
    api.get<Record<string, number>>('/prices', { params: { tickers: tickers.join(',') } }).then(r => r.data),
  addSnapshot: (id: number, balance: number, date: string) =>
    api.post<BalanceSnapshot>(`/accounts/${id}/history`, { balance, date }).then(r => r.data),
}
