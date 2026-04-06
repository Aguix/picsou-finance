import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, Legend, Line, XAxis, YAxis } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { formatDate, formatCurrency } from '@/lib/utils'

interface NetWorthChartProps {
  data: { date: string; total: number; invested: number }[]
}

function NetWorthTooltip({ active, payload, labels }: {
  active?: boolean
  payload?: Array<{
    value: number
    dataKey: string
    payload: { date: string; total: number; invested: number }
  }>
  labels: { total: string; invested: string; gainLoss: string; locale: string; currency: string }
}) {
  if (!active || !payload?.length) return null

  const totalItem = payload.find(p => p.dataKey === 'total')
  const investedItem = payload.find(p => p.dataKey === 'invested')
  if (!totalItem || !investedItem) return null

  const total = totalItem.value as number
  const invested = investedItem.value as number
  const gainLoss = total - invested
  const dateStr = totalItem.payload?.date

  return (
    <div className="rounded-xl bg-popover px-3 py-2.5 text-xs text-popover-foreground shadow-lg ring-1 ring-foreground/5 dark:ring-foreground/10">
      <div className="mb-1.5 font-medium">{formatDate(dateStr, labels.locale)}</div>
      <div className="flex items-center gap-2 py-0.5">
        <div
          className="h-0.5 w-4 shrink-0 rounded-full"
          style={{ backgroundColor: 'var(--color-total)' }}
        />
        <span className="text-muted-foreground">{labels.total}</span>
        <span className="ml-auto font-mono font-medium tabular-nums">
          {formatCurrency(total, labels.currency, labels.locale)}
        </span>
      </div>
      <div className="flex items-center gap-2 py-0.5">
        <div
          className="h-0.5 w-4 shrink-0 border-t-2 border-dashed"
          style={{ borderColor: 'var(--color-invested)' }}
        />
        <span className="text-muted-foreground">{labels.invested}</span>
        <span className="ml-auto font-mono font-medium tabular-nums">
          {formatCurrency(invested, labels.currency, labels.locale)}
        </span>
      </div>
      <div className="my-1.5 border-t border-border" />
      <div className="flex items-center justify-between py-0.5">
        <span className={`font-mono font-medium tabular-nums ${gainLoss >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
          {gainLoss >= 0 ? '+' : ''}{formatCurrency(gainLoss, labels.currency, labels.locale)}
        </span>
        <span className="text-muted-foreground">{labels.gainLoss}</span>
      </div>
    </div>
  )
}

export function NetWorthChart({ data }: NetWorthChartProps) {
  const { t } = useTranslation()
  const locale = t('common.locale')

  const chartConfig = useMemo(() => ({
    total: {
      label: t('dashboard.netWorth'),
      color: 'var(--chart-1)',
    },
    invested: {
      label: t('dashboard.invested'),
      color: 'var(--chart-5)',
    },
  }) satisfies ChartConfig, [t])

  const labels = useMemo(() => ({
    total: t('dashboard.netWorth'),
    invested: t('dashboard.invested'),
    gainLoss: t('dashboard.gainLoss'),
    locale: t('common.locale'),
    currency: t('common.currency'),
  }), [t])

  return (
    <ChartContainer config={chartConfig} className="h-[250px] w-full [&>div>div]:!w-full [&>div>div>svg]:!w-full">
      <AreaChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="fillTotal" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="var(--color-total)" stopOpacity={0.3} />
            <stop offset="95%" stopColor="var(--color-total)" stopOpacity={0.05} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="date"
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={(value) => new Date(value).toLocaleDateString(locale, { month: 'short' })}
        />
        <YAxis
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={(value) => `${(value / 1000).toFixed(0)}k`}
          width={45}
        />
        <ChartTooltip content={<NetWorthTooltip labels={labels} />} />
        <Area
          dataKey="total"
          type="monotone"
          fill="url(#fillTotal)"
          stroke="var(--color-total)"
          strokeWidth={2}
        />
        <Line
          dataKey="invested"
          type="monotone"
          stroke="var(--color-invested)"
          strokeWidth={2}
          strokeDasharray="6 4"
          dot={false}
          activeDot={false}
        />
        <Legend content={() => (
          <div className="flex items-center justify-center gap-5 pt-2">
            <div className="flex items-center gap-1.5 text-xs">
              <div
                className="h-0.5 w-4 rounded-full"
                style={{ backgroundColor: 'var(--color-total)' }}
              />
              <span className="text-muted-foreground">{labels.total}</span>
            </div>
            <div className="flex items-center gap-1.5 text-xs">
              <div
                className="h-0.5 w-4 border-t-2 border-dashed"
                style={{ borderColor: 'var(--color-invested)' }}
              />
              <span className="text-muted-foreground">{labels.invested}</span>
            </div>
          </div>
        )} />
      </AreaChart>
    </ChartContainer>
  )
}
