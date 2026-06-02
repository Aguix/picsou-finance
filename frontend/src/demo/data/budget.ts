import type {
  AllocationResponse,
  Budget,
  BudgetSettings,
  CashflowBucket,
  CashflowPeriod,
  CashflowResponse,
  CategorizationRule,
  Category,
  RecurringOccurrence,
  RecurringSeries,
  UncategorizedTransaction,
} from '@/types/api'

// ── Date helpers ──────────────────────────────────────────────────────────────
// Demo data is generated relative to "today" so the Budget section always looks
// alive. The payday cycle is the calendar month here (cycleStartDay = 1).

function iso(d: Date): string {
  return d.toISOString().split('T')[0]
}

const NOW = new Date()
const CYCLE_START = new Date(NOW.getFullYear(), NOW.getMonth(), 1)
const CYCLE_END = new Date(NOW.getFullYear(), NOW.getMonth() + 1, 0)

function daysFromNow(n: number): string {
  const d = new Date(NOW)
  d.setDate(d.getDate() + n)
  return iso(d)
}

// ── Categories ────────────────────────────────────────────────────────────────

export const mockCategories: Category[] = [
  { id: 1, name: 'Salaire', kind: 'INCOME', color: '#10b981', icon: null, isDefault: true, archived: false, sortOrder: 0 },
  { id: 2, name: 'Courses', kind: 'EXPENSE', color: '#f59e0b', icon: null, isDefault: true, archived: false, sortOrder: 1 },
  { id: 3, name: 'Logement', kind: 'EXPENSE', color: '#6366f1', icon: null, isDefault: true, archived: false, sortOrder: 2 },
  { id: 4, name: 'Transport', kind: 'EXPENSE', color: '#0ea5e9', icon: null, isDefault: true, archived: false, sortOrder: 3 },
  { id: 5, name: 'Loisirs', kind: 'EXPENSE', color: '#ec4899', icon: null, isDefault: true, archived: false, sortOrder: 4 },
  { id: 6, name: 'Abonnements', kind: 'EXPENSE', color: '#8b5cf6', icon: null, isDefault: true, archived: false, sortOrder: 5 },
  { id: 7, name: 'Restaurants', kind: 'EXPENSE', color: '#ef4444', icon: null, isDefault: true, archived: false, sortOrder: 6 },
  { id: 8, name: 'Épargne', kind: 'TRANSFER', color: '#22c55e', icon: null, isDefault: true, archived: false, sortOrder: 7 },
  { id: 9, name: 'Investissement', kind: 'TRANSFER', color: '#14b8a6', icon: null, isDefault: true, archived: false, sortOrder: 8 },
]

// ── Categorization rules ──────────────────────────────────────────────────────

export const mockRules: CategorizationRule[] = [
  { id: 1, matchType: 'COUNTERPARTY', pattern: 'NETFLIX', categoryId: 6, categoryName: 'Abonnements', priority: 0, source: 'USER' },
  { id: 2, matchType: 'KEYWORD', pattern: 'CARREFOUR', categoryId: 2, categoryName: 'Courses', priority: 1, source: 'AUTO' },
  { id: 3, matchType: 'COUNTERPARTY', pattern: 'TOTALENERGIES', categoryId: 3, categoryName: 'Logement', priority: 2, source: 'AUTO' },
]

// ── To-categorize inbox ───────────────────────────────────────────────────────

export const mockUncategorized: UncategorizedTransaction[] = [
  {
    id: 9001, date: daysFromNow(-2), description: 'AMAZON EU SARL', amount: -42.9, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-2), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'AMAZON EU SARL',
  },
  {
    id: 9002, date: daysFromNow(-3), description: 'SNCF VOYAGEURS', amount: -68.0, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-3), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'SNCF VOYAGEURS',
  },
  {
    id: 9003, date: daysFromNow(-5), description: 'BOULANGERIE DU COIN', amount: -8.4, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-5), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'BOULANGERIE DU COIN',
  },
]

// ── Envelopes ─────────────────────────────────────────────────────────────────

function envelope(id: number, cat: Category, limit: number, spent: number): Budget {
  const remaining = Math.round((limit - spent) * 100) / 100
  const percent = limit > 0 ? Math.round((spent / limit) * 100) : 0
  return {
    id,
    categoryId: cat.id,
    categoryName: cat.name,
    categoryKind: cat.kind,
    categoryColor: cat.color,
    categoryIcon: cat.icon,
    monthlyLimit: limit,
    spent,
    remaining,
    percent,
    overBudget: spent > limit,
    cycleStart: iso(CYCLE_START),
    cycleEnd: iso(CYCLE_END),
  }
}

export const mockBudgets: Budget[] = [
  envelope(1, mockCategories[1], 500, 412.3), // Courses
  envelope(2, mockCategories[2], 1100, 1100), // Logement (at limit)
  envelope(3, mockCategories[3], 200, 168.5), // Transport
  envelope(4, mockCategories[4], 150, 187.9), // Loisirs (over)
  envelope(5, mockCategories[5], 90, 53.97), // Abonnements
  envelope(6, mockCategories[6], 250, 134.2), // Restaurants
]

// ── Settings ──────────────────────────────────────────────────────────────────

export const mockBudgetSettings: BudgetSettings = {
  cycleStartDay: 1,
  currentCycleStart: iso(CYCLE_START),
  currentCycleEnd: iso(CYCLE_END),
}

// ── Cashflow ──────────────────────────────────────────────────────────────────

const MONTH_LABELS = ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Juin', 'Juil', 'Août', 'Sep', 'Oct', 'Nov', 'Déc']

export function mockCashflow(period: CashflowPeriod): CashflowResponse {
  if (period === 'YTD') {
    const series: CashflowBucket[] = []
    let income = 0
    let expense = 0
    for (let m = 0; m <= NOW.getMonth(); m++) {
      const inc = 3200 + ((m * 37) % 250)
      const exp = 1950 + ((m * 53) % 600)
      income += inc
      expense += exp
      series.push({
        start: iso(new Date(NOW.getFullYear(), m, 1)),
        end: iso(new Date(NOW.getFullYear(), m + 1, 0)),
        label: MONTH_LABELS[m],
        income: inc,
        expense: exp,
        net: inc - exp,
      })
    }
    return { period, from: iso(new Date(NOW.getFullYear(), 0, 1)), to: iso(NOW), income, expense, net: income - expense, series }
  }

  // CYCLE — four weekly buckets within the current month.
  const weekly = [
    { label: 'S1', income: 3200, expense: 720 },
    { label: 'S2', income: 0, expense: 540 },
    { label: 'S3', income: 0, expense: 610 },
    { label: 'S4', income: 0, expense: 387 },
  ]
  const series: CashflowBucket[] = weekly.map((w, i) => {
    const s = new Date(CYCLE_START)
    s.setDate(1 + i * 7)
    const e = new Date(CYCLE_START)
    e.setDate(Math.min(7 + i * 7, CYCLE_END.getDate()))
    return { start: iso(s), end: iso(e), label: w.label, income: w.income, expense: w.expense, net: w.income - w.expense }
  })
  const income = weekly.reduce((a, w) => a + w.income, 0)
  const expense = weekly.reduce((a, w) => a + w.expense, 0)
  return { period, from: iso(CYCLE_START), to: iso(CYCLE_END), income, expense, net: income - expense, series }
}

// ── Allocation ────────────────────────────────────────────────────────────────

export function mockAllocation(period: CashflowPeriod): AllocationResponse {
  const stock = [
    { assetClass: 'CURRENT' as const, amount: 3920, percent: 11 },
    { assetClass: 'SAVINGS' as const, amount: 12920, percent: 36 },
    { assetClass: 'INVESTMENT' as const, amount: 18770, percent: 53 },
  ]
  const totalStock = stock.reduce((a, s) => a + s.amount, 0)
  // YTD shows the accumulated transfers; CYCLE shows just this month's.
  const factor = period === 'YTD' ? 6 : 1
  const contributions = [
    { accountId: 1, accountName: 'Livret A', assetClass: 'SAVINGS' as const, color: '#22c55e', amount: 300 * factor },
    { accountId: 7, accountName: 'LEP', assetClass: 'SAVINGS' as const, color: '#16a34a', amount: 150 * factor },
    { accountId: 2, accountName: 'PEA', assetClass: 'INVESTMENT' as const, color: '#8b5cf6', amount: 400 * factor },
  ]
  const totalContributions = contributions.reduce((a, c) => a + c.amount, 0)
  return {
    period,
    from: period === 'YTD' ? iso(new Date(NOW.getFullYear(), 0, 1)) : iso(CYCLE_START),
    to: iso(period === 'YTD' ? NOW : CYCLE_END),
    totalStock,
    stock,
    totalContributions,
    contributions,
  }
}

// ── Recurring ─────────────────────────────────────────────────────────────────

export const mockRecurring: RecurringSeries[] = [
  { id: 1, label: 'Netflix', counterparty: 'NETFLIX.COM', expectedAmount: -13.49, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(6), lastSeenDate: daysFromNow(-24), categoryId: 6, categoryName: 'Abonnements', categoryColor: '#8b5cf6', categoryIcon: null },
  { id: 2, label: 'Loyer', counterparty: 'AGENCE IMMO', expectedAmount: -1100, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(12), lastSeenDate: daysFromNow(-18), categoryId: 3, categoryName: 'Logement', categoryColor: '#6366f1', categoryIcon: null },
  { id: 3, label: 'Spotify', counterparty: 'SPOTIFY', expectedAmount: -10.99, cadence: 'MONTHLY', status: 'SUGGESTED', nextDueDate: daysFromNow(9), lastSeenDate: daysFromNow(-21), categoryId: 6, categoryName: 'Abonnements', categoryColor: '#8b5cf6', categoryIcon: null },
  { id: 4, label: 'Salaire', counterparty: 'EMPLOYEUR SAS', expectedAmount: 3200, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(20), lastSeenDate: daysFromNow(-10), categoryId: 1, categoryName: 'Salaire', categoryColor: '#10b981', categoryIcon: null },
  { id: 5, label: 'Assurance auto', counterparty: 'MAIF', expectedAmount: -42.6, cadence: 'MONTHLY', status: 'IGNORED', nextDueDate: daysFromNow(15), lastSeenDate: daysFromNow(-16), categoryId: 4, categoryName: 'Transport', categoryColor: '#0ea5e9', categoryIcon: null },
]

/** Project upcoming occurrences from the confirmed/suggested series across the horizon. */
export function mockCalendar(horizonDays: number): RecurringOccurrence[] {
  const occ: RecurringOccurrence[] = []
  for (const s of mockRecurring) {
    if (s.status === 'IGNORED' || !s.nextDueDate) continue
    let due = new Date(`${s.nextDueDate}T00:00:00`)
    const limit = new Date(NOW)
    limit.setDate(limit.getDate() + horizonDays)
    while (due <= limit) {
      occ.push({
        seriesId: s.id,
        label: s.label,
        counterparty: s.counterparty,
        expectedAmount: s.expectedAmount,
        dueDate: iso(due),
        categoryId: s.categoryId,
        categoryName: s.categoryName,
        categoryColor: s.categoryColor,
        categoryIcon: s.categoryIcon,
      })
      // monthly cadence step (all demo series are monthly)
      due = new Date(due)
      due.setMonth(due.getMonth() + 1)
    }
  }
  return occ.sort((a, b) => a.dueDate.localeCompare(b.dueDate))
}
