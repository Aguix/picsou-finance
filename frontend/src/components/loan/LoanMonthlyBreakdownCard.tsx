import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { LoanSummary } from '@/types/api'

interface LoanMonthlyBreakdownCardProps {
  summary: LoanSummary
}

export function LoanMonthlyBreakdownCard({ summary }: LoanMonthlyBreakdownCardProps) {
  const { t } = useTranslation()

  const items = [
    { label: t('debt.capital'), value: summary.monthlyCapital, color: 'bg-emerald-500' },
    { label: t('debt.interest'), value: summary.monthlyInterest, color: 'bg-amber-500' },
    { label: t('debt.insurance'), value: summary.monthlyInsurance, color: 'bg-sky-500' },
  ]

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base flex items-center justify-between">
          <span>{t('debt.monthlyBreakdown')}</span>
          <CurrencyDisplay value={summary.monthlyPayment} className="text-base font-semibold" />
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="flex flex-col gap-3 md:flex-row md:gap-6">
          {items.map(item => (
            <div key={item.label} className="flex items-center gap-2 flex-1 min-w-0">
              <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${item.color}`} />
              <span className="text-xs text-muted-foreground truncate">{item.label}</span>
              <CurrencyDisplay
                value={item.value}
                className="ml-auto text-sm font-medium tabular-nums"
              />
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}
