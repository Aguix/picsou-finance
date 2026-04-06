import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useDashboard } from '@/features/dashboard/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PageHeader } from '@/components/shared/PageHeader'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { DistributionPie } from '@/components/shared/DistributionPie'
import { GoalProgressBar } from '@/components/shared/GoalProgressBar'
import { TimeRangeSelector, type TimeRange } from '@/components/shared/TimeRangeSelector'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { HugeiconsIcon } from '@hugeicons/react'
import { TrendingUp, TrendingDown } from '@hugeicons/core-free-icons'
import { todayLabel } from '@/lib/utils'

export function DashboardPage() {
  const { t } = useTranslation()
  const [range, setRange] = useState<TimeRange>('1Y')
  const { data, isLoading } = useDashboard(range)

  if (isLoading || !data) {
    return <LoadingSkeleton />
  }

  const trend = data.totalNetWorth - data.previousTotal
  const trendPct =
    data.previousTotal > 0 ? ((trend / data.previousTotal) * 100).toFixed(1) : null
  const trendPositive = trend >= 0

  return (
    <div className="space-y-6">
      <PageHeader
        surtitle={todayLabel()}
        title={t('dashboard.title')}
        actions={<TimeRangeSelector value={range} onChange={setRange} />}
      />

      {/* Net worth hero */}
      <Card>
        <CardContent className="pt-6">
          <p className="text-sm text-muted-foreground mb-1">{t('dashboard.netWorth')}</p>
          <CurrencyDisplay value={data.totalNetWorth} className="text-4xl font-bold" />

          <div className="mt-3 flex items-center gap-2">
            <HugeiconsIcon
              icon={trendPositive ? TrendingUp : TrendingDown}
              className={trendPositive ? 'text-emerald-500' : 'text-red-500'}
              size={18}
            />
            <span
              className={`text-sm font-medium ${trendPositive ? 'text-emerald-500' : 'text-red-500'}`}
            >
              <CurrencyDisplay value={trend} />
              {trendPct !== null && (
                <span className="ml-1 font-normal text-muted-foreground">
                  ({trendPositive ? '+' : ''}{trendPct}%)
                </span>
              )}
            </span>
            <span className="text-sm text-muted-foreground">{t('dashboard.netWorthChange')}</span>
          </div>
        </CardContent>
      </Card>

      {/* Charts row */}
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t('dashboard.gainLoss')}</CardTitle>
          </CardHeader>
          <CardContent>
            <NetWorthChart data={data.netWorthHistory} />
          </CardContent>
        </Card>

        <DistributionPie data={data.distribution} />
      </div>

      {/* Goals section */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle>{t('dashboard.goals')}</CardTitle>
          {data.goalSummaries.length > 0 && (
            <Button variant="ghost" size="sm" asChild>
              <Link to="/goals">{t('dashboard.viewAll')}</Link>
            </Button>
          )}
        </CardHeader>
        <CardContent>
          {data.goalSummaries.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('dashboard.noGoals')}</p>
          ) : (
            <div className="space-y-4">
              {data.goalSummaries.slice(0, 3).map((goal) => (
                <GoalProgressBar key={goal.id} goal={goal} />
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
