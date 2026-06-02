import type { AccountType } from '@/types/api'

export const ACCOUNT_TYPES: { value: AccountType; labelKey: string }[] = [
  { value: 'CHECKING', labelKey: 'accountTypes.checking' },
  { value: 'SAVINGS', labelKey: 'accountTypes.savings' },
  { value: 'LEP', labelKey: 'accountTypes.lep' },
  { value: 'PEA', labelKey: 'accountTypes.pea' },
  { value: 'COMPTE_TITRES', labelKey: 'accountTypes.compteTitres' },
  { value: 'CRYPTO', labelKey: 'accountTypes.crypto' },
  { value: 'REAL_ESTATE', labelKey: 'accountTypes.realEstate' },
  { value: 'LOAN', labelKey: 'accountTypes.loan' },
  { value: 'OTHER', labelKey: 'accountTypes.other' },
]

export const ACCOUNT_COLORS = [
  '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#f43f5e', '#ef4444', '#f97316',
  '#eab308', '#84cc16', '#22c55e', '#10b981',
  '#14b8a6', '#06b6d4', '#0ea5e9', '#3b82f6',
]

export const QUERY_STALE_TIMES = {
  dashboard: 5 * 60 * 1000,
  accounts: 1 * 60 * 1000,
  accountDetail: 2 * 60 * 1000,
  sync: 30 * 1000,
  goals: 2 * 60 * 1000,
} as const
