import type { LucideIcon } from 'lucide-react'
import {
  CalendarClock,
  Inbox,
  LayoutGrid,
  PieChart,
  Settings2,
  TrendingUp,
  Wallet,
} from 'lucide-react'

/** The Budget section is a single page (one nav item) with these in-page tabs. */
export type BudgetTab =
  | 'overview'
  | 'envelopes'
  | 'cashflow'
  | 'allocation'
  | 'recurring'
  | 'categorize'
  | 'manage'

export const BUDGET_TABS: { value: BudgetTab; labelKey: string; icon: LucideIcon }[] = [
  { value: 'overview', labelKey: 'budget.tab.overview', icon: LayoutGrid },
  { value: 'envelopes', labelKey: 'budget.tab.envelopes', icon: Wallet },
  { value: 'cashflow', labelKey: 'budget.tab.cashflow', icon: TrendingUp },
  { value: 'allocation', labelKey: 'budget.tab.allocation', icon: PieChart },
  { value: 'recurring', labelKey: 'budget.tab.recurring', icon: CalendarClock },
  { value: 'categorize', labelKey: 'budget.tab.categorize', icon: Inbox },
  { value: 'manage', labelKey: 'budget.tab.manage', icon: Settings2 },
]
