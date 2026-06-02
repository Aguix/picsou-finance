import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PageHeader } from '@/components/shared/PageHeader'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import { useUncategorized } from '@/features/budget/hooks'
import { BUDGET_TABS, type BudgetTab } from './tabs'
import { OverviewTab } from './OverviewTab'
import { EnvelopesTab } from './EnvelopesTab'
import { CashflowTab } from './CashflowTab'
import { AllocationTab } from './AllocationTab'
import { RecurringTab } from './RecurringTab'
import { CategorizeTab } from './CategorizeTab'
import { ManageTab } from './ManageTab'

/**
 * The whole Budget module lives behind a single nav item (design "option B"):
 * a recap overview plus drill-down tabs for envelopes, cashflow, allocation,
 * recurring payments and categorization. Tab state is local — each tab fetches
 * its own data through TanStack Query, so switching is instant once warm.
 */
export function BudgetPage() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<BudgetTab>('overview')
  const { data: uncategorized } = useUncategorized()
  const toCategorize = uncategorized?.length ?? 0

  return (
    <div>
      <PageHeader surtitle={t('budget.surtitle')} title={t('budget.title')} />

      <Tabs value={tab} onValueChange={(v) => setTab(v as BudgetTab)}>
        {/* Horizontal scroll keeps all tabs reachable on narrow phones. */}
        <div className="-mx-1 overflow-x-auto px-1 pb-1">
          <TabsList className="w-max">
            {BUDGET_TABS.map(({ value, labelKey, icon: Icon }) => (
              <TabsTrigger key={value} value={value} className="gap-1.5">
                <Icon className="size-4" />
                <span>{t(labelKey)}</span>
                {value === 'categorize' && toCategorize > 0 && (
                  <Badge variant="secondary" className="ml-1">{toCategorize}</Badge>
                )}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        <div className="mt-4">
          <TabsContent value="overview"><OverviewTab onNavigate={setTab} /></TabsContent>
          <TabsContent value="envelopes"><EnvelopesTab /></TabsContent>
          <TabsContent value="cashflow"><CashflowTab /></TabsContent>
          <TabsContent value="allocation"><AllocationTab /></TabsContent>
          <TabsContent value="recurring"><RecurringTab /></TabsContent>
          <TabsContent value="categorize"><CategorizeTab /></TabsContent>
          <TabsContent value="manage"><ManageTab /></TabsContent>
        </div>
      </Tabs>
    </div>
  )
}
