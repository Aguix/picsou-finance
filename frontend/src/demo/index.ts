import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { GoalProgress } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { mockDashboard } from './data/dashboard'
import { mockGoals } from './data/goals'
import { mockHoldings } from './data/holdings'
import { mockTransactions } from './data/transactions'
import { mockExchangeStatuses, mockWalletStatuses, mockRequisitions } from './data/sync-status'

function randomDelay(): number {
  return 200 + Math.random() * 400
}

type MockHandler = (config: InternalAxiosRequestConfig) => any

const handlers = new Map<string, MockHandler>()

function key(method: string, url: string): string {
  const normalized = url.split('?')[0].replace(/\/$/, '')
  return `${method.toUpperCase()} ${normalized}`
}

// Auth
handlers.set(key('POST', '/auth/login'), () => ({ username: 'demo' }))
handlers.set(key('POST', '/auth/refresh'), () => ({ username: 'demo' }))

// Dashboard
handlers.set(key('GET', '/dashboard'), () => mockDashboard)

// Accounts
handlers.set(key('GET', '/accounts'), () => mockAccounts)
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}`), () => mockAccounts[i - 1])
}

// Account CRUD
handlers.set(key('POST', '/accounts'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(),
    name: body.name ?? 'New Account',
    type: body.type ?? 'CHECKING',
    provider: body.provider ?? null,
    currency: body.currency ?? 'EUR',
    currentBalance: body.currentBalance ?? 0,
    currentBalanceEur: body.currentBalance ?? 0,
    lastSyncedAt: null,
    isManual: body.isManual ?? true,
    color: body.color ?? '#6366f1',
    ticker: body.ticker ?? null,
    createdAt: new Date().toISOString(),
  }
})
handlers.set(key('PUT', '/accounts/1'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return { ...mockAccounts[0], ...body }
})
handlers.set(key('DELETE', '/accounts/1'), () => ({}))

// Account details: holdings for PEA (id=2), Compte Titres (id=3), Crypto (id=6)
handlers.set(key('GET', '/accounts/2/holdings'), () => mockHoldings[2] ?? [])
handlers.set(key('GET', '/accounts/3/holdings'), () => mockHoldings[3] ?? [])
handlers.set(key('GET', '/accounts/6/holdings'), () => mockHoldings[6] ?? [])

// Account details: transactions for all accounts
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}/transactions`), () => mockTransactions[i] ?? [])
}

// Account details: history for multiple accounts
handlers.set(key('GET', '/accounts/1/history'), () => [
  { id: 1, date: '2025-01-01', balance: 7200 },
  { id: 2, date: '2025-02-01', balance: 7500 },
  { id: 3, date: '2025-03-01', balance: 7800 },
])

handlers.set(key('GET', '/accounts/2/history'), () => [
  { id: 4, date: '2025-01-01', balance: 10200 },
  { id: 5, date: '2025-02-01', balance: 11300 },
  { id: 6, date: '2025-03-01', balance: 12450.5 },
])

handlers.set(key('GET', '/accounts/3/history'), () => [
  { id: 7, date: '2025-01-01', balance: 7100 },
  { id: 8, date: '2025-02-01', balance: 7800 },
  { id: 9, date: '2025-03-01', balance: 8320.75 },
])

handlers.set(key('GET', '/accounts/4/history'), () => [
  { id: 10, date: '2025-01-01', balance: 1800 },
  { id: 11, date: '2025-02-01', balance: 2100 },
  { id: 12, date: '2025-03-01', balance: 2340.2 },
])

handlers.set(key('GET', '/accounts/5/history'), () => [
  { id: 13, date: '2025-01-01', balance: 1200 },
  { id: 14, date: '2025-02-01', balance: 1400 },
  { id: 15, date: '2025-03-01', balance: 1580.9 },
])

handlers.set(key('GET', '/accounts/6/history'), () => [
  { id: 16, date: '2025-01-01', balance: 3200 },
  { id: 17, date: '2025-02-01', balance: 3700 },
  { id: 18, date: '2025-03-01', balance: 4250 },
])

handlers.set(key('GET', '/accounts/7/history'), () => [
  { id: 19, date: '2025-01-01', balance: 4800 },
  { id: 20, date: '2025-02-01', balance: 4960 },
  { id: 21, date: '2025-03-01', balance: 5120 },
])

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
for (let i = 1; i <= 3; i++) {
  handlers.set(key('GET', `/goals/${i}`), () => mockGoals[i - 1])
  handlers.set(key('GET', `/goals/${i}/months`), () => generateMockMonths(mockGoals[i - 1]))
}
handlers.set(key('POST', '/goals'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockGoals[0],
    id: Date.now(),
    name: body.name ?? 'New Goal',
    targetAmount: body.targetAmount ?? 0,
    deadline: body.deadline ?? '2026-01-01',
    accounts: (body.accountIds ?? []).map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    currentTotal: 0,
    percentComplete: 0,
    monthsLeft: 6,
    monthlyNeeded: 0,
    avgMonthlyContribution: null,
    isOnTrack: true,
    surplus: 0,
  }
})
for (let i = 1; i <= 3; i++) {
  handlers.set(key('PUT', `/goals/${i}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    return {
      ...mockGoals[i - 1],
      name: body.name ?? mockGoals[i - 1].name,
      targetAmount: body.targetAmount ?? mockGoals[i - 1].targetAmount,
      deadline: body.deadline ?? mockGoals[i - 1].deadline,
      accounts: (body.accountIds ?? mockGoals[i - 1].accounts.map(a => a.id))
        .map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    }
  })
}
handlers.set(key('DELETE', '/goals/1'), () => null)
handlers.set(key('DELETE', '/goals/2'), () => null)
handlers.set(key('DELETE', '/goals/3'), () => null)

// Sync
handlers.set(key('GET', '/sync/status'), () => mockRequisitions)
handlers.set(key('GET', '/sync/institutions'), () => [
  { id: 'BNP_PARIBAS', name: 'BNP Paribas', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
  { id: 'BOURSOBANK', name: 'BoursoBank', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
])

// Crypto exchange
handlers.set(key('GET', '/crypto/exchange/status'), () => mockExchangeStatuses)

// Crypto wallet
handlers.set(key('GET', '/crypto/wallet'), () => mockWalletStatuses)

// Sync - initiate
handlers.set(key('POST', '/sync/initiate'), () => ({
  requisitionId: 'demo-req-' + Date.now(),
  authLink: 'https://demo.enablebanking.com/auth?demo=true',
}))

// Sync - complete
handlers.set(key('POST', '/sync/complete'), () => ([
  { id: 100, name: 'Demo Bank Account', type: 'CHECKING' as const, provider: 'Demo Bank', currency: 'EUR', currentBalance: 5000, currentBalanceEur: 5000, lastSyncedAt: new Date().toISOString(), isManual: false, color: '#3b82f6', ticker: null, createdAt: new Date().toISOString() }
]))

// Sync - retry
handlers.set(key('POST', '/sync/1/retry'), () => [])

// Sync - delete
handlers.set(key('DELETE', '/sync/1'), () => null)

// Trade Republic - session status
handlers.set(key('GET', '/tr/status'), () => ({ isActive: false, expiresAt: null }))

// Trade Republic - initiate auth
handlers.set(key('POST', '/tr/auth/initiate'), () => ({ processId: 'demo-tr-process' }))

// Trade Republic - complete auth
handlers.set(key('POST', '/tr/auth/complete'), () => [])

// Trade Republic - sync
handlers.set(key('POST', '/tr/sync'), () => [])

// Trade Republic - import CSV
handlers.set(key('POST', '/tr/import'), () => [])

// Trade Republic - logout
handlers.set(key('POST', '/tr/logout'), () => null)

// Crypto exchange - add
handlers.set(key('POST', '/crypto/exchange'), () => ({
  id: Date.now(), name: 'Binance', type: 'CRYPTO' as const, provider: 'BINANCE', currency: 'USDT', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#f59e0b', ticker: null, createdAt: new Date().toISOString()
}))

// Crypto exchange - sync
handlers.set(key('POST', '/crypto/exchange/1/sync'), () => [])

// Crypto exchange - remove
handlers.set(key('DELETE', '/crypto/exchange/1'), () => null)

// Crypto wallet - add
handlers.set(key('POST', '/crypto/wallet'), () => ({
  id: Date.now(), name: 'ETH Wallet', type: 'CRYPTO' as const, provider: null, currency: 'ETH', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#8b5cf6', ticker: 'ETH', createdAt: new Date().toISOString()
}))

// Crypto wallet - sync
handlers.set(key('POST', '/crypto/wallet/1/sync'), () => [])

// Crypto wallet - remove
handlers.set(key('DELETE', '/crypto/wallet/1'), () => null)

// Finary - configured
handlers.set(key('GET', '/finary/configured'), () => true)

// Finary - preview file
handlers.set(key('POST', '/finary/preview'), () => ({
  accounts: [
    { finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
    { finaryName: 'PEA', finaryInstitution: 'BoursoBank', finaryCategory: 'pea', suggestedType: 'PEA' as const, currentBalance: 8000, nativeCurrency: 'EUR', transactionCount: 15 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 57,
  fileToken: 'demo-file-token',
}))

// Finary - import
handlers.set(key('POST', '/finary/import'), () => ({
  accountsCreated: 1,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 3,
  transactionsImported: 57,
  importedAccounts: [
    { id: 100, name: 'PEA Finary', type: 'PEA' as const, currentBalance: 8000, color: '#10b981' },
  ],
}))

// Finary - API sync preview
handlers.set(key('POST', '/finary/api-sync/preview'), () => ({
  accounts: [
    { finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 42,
  syncToken: 'demo-sync-token',
}))

// Finary - API sync execute
handlers.set(key('POST', '/finary/api-sync/execute'), () => ({
  accountsCreated: 0,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 2,
  transactionsImported: 42,
  importedAccounts: [],
}))

function generateMockMonths(goal: GoalProgress) {
  const start = new Date('2025-01-01')
  const end = new Date(goal.deadline)
  const months: { yearMonth: string; objective: number; actual: number | null; manualActual: number | null; override: number | null; effective: number | null }[] = []
  const current = new Date(start)
  const now = new Date()
  while (current <= end) {
    const ym = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}`
    const isPast = current <= now
    const actual = isPast ? Math.round((goal.monthlyNeeded * (0.7 + Math.random() * 0.6)) * 100) / 100 : null
    months.push({
      yearMonth: ym,
      objective: goal.monthlyNeeded,
      actual,
      manualActual: null,
      override: null,
      effective: actual,
    })
    current.setMonth(current.getMonth() + 1)
  }
  return months
}

export function createDemoAdapter() {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const k = key(config.method || 'GET', config.url || '')
    const handler = handlers.get(k)

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          data: handler ? handler(config) : {},
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse)
      }, randomDelay())
    })
  }
}
