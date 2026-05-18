import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, Legend, Line, XAxis, YAxis } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { TimeRangeSelector, type TimeRange } from '@/components/shared/TimeRangeSelector'
import { formatDate, formatCurrency } from '@/lib/utils'
import { EmptyChartState } from '@/components/shared/EmptyChartState'
import type { IntradayPoint } from '@/features/dashboard/api'

interface NetWorthChartProps {
  data: { date: string; total: number; invested?: number }[]
  intraday?: IntradayPoint[]
  range: TimeRange
  onRangeChange: (range: TimeRange) => void
  // Hide the "Capital invested" dashed line + legend entry. When omitted, the
  // line is shown only if at least one data point carries `invested`.
  showInvested?: boolean
  // Optional ideal-trajectory overlay: linear line from (startDate, startValue)
  // to (endDate, endValue). On ALL range, X axis is stretched to endDate so the
  // user can see the projection beyond today.
  target?: {
    startDate: string  // ISO instant or date
    startValue: number
    endDate: string    // ISO date
    endValue: number
    label: string      // tooltip + legend label
  }
}

function NetWorthTooltip({ active, payload, labels, is24H }: {
  active?: boolean
  payload?: Array<{
    value: number
    dataKey: string
    payload: { date?: string; timestamp?: string; total: number | null; invested?: number; target?: number }
  }>
  labels: { total: string; invested: string; target: string; gainLoss: string; locale: string; currency: string }
  is24H: boolean
}) {
  if (!active || !payload?.length) return null

  // Prefer the total item for label/date; fall back to any payload entry so the
  // tooltip still works for synthetic future points where total is null.
  const totalItem = payload.find(p => p.dataKey === 'total' && p.value != null)
  const anchorItem = totalItem ?? payload[0]
  if (!anchorItem) return null

  const total = totalItem ? (totalItem.value as number) : null
  const investedItem = payload.find(p => p.dataKey === 'invested' && p.value != null)
  const hasInvested = investedItem != null
  const invested = hasInvested ? (investedItem.value as number) : 0
  const gainLoss = total != null && hasInvested ? total - invested : null
  const targetItem = payload.find(p => p.dataKey === 'target' && p.value != null)
  const target = targetItem ? (targetItem.value as number) : null

  const dateStr = is24H ? anchorItem.payload?.timestamp : anchorItem.payload?.date
  const formattedDate = is24H && dateStr
    ? new Date(dateStr).toLocaleString(labels.locale, { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })
    : formatDate(dateStr, labels.locale)

  return (
    <div className="rounded-xl bg-popover px-3 py-2.5 text-xs text-popover-foreground shadow-lg ring-1 ring-foreground/5 dark:ring-foreground/10">
      <div className="mb-1.5 font-medium">{formattedDate}</div>
      {total != null && (
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
      )}
      {hasInvested && (
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
      )}
      {target != null && (
        <div className="flex items-center gap-2 py-0.5">
          <div
            className="h-0.5 w-4 shrink-0 border-t-2 border-dashed"
            style={{ borderColor: 'var(--color-target)' }}
          />
          <span className="text-muted-foreground">{labels.target}</span>
          <span className="ml-auto font-mono font-medium tabular-nums">
            {formatCurrency(target, labels.currency, labels.locale)}
          </span>
        </div>
      )}
      {hasInvested && gainLoss !== null && (
        <>
          <div className="my-1.5 border-t border-border" />
          <div className="flex items-center justify-between py-0.5">
            <span className={`font-mono font-medium tabular-nums ${gainLoss >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
              {gainLoss >= 0 ? '+' : ''}{formatCurrency(gainLoss, labels.currency, labels.locale)}
            </span>
            <span className="text-muted-foreground">{labels.gainLoss}</span>
          </div>
        </>
      )}
    </div>
  )
}

function filterByRange(data: NetWorthChartProps['data'], range: TimeRange) {
  if (range === 'ALL') return data
  const now = new Date()
  let from: Date
  switch (range) {
    case '7D': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7); break
    case '1M': from = new Date(now.getFullYear(), now.getMonth() - 1, now.getDate()); break
    case '3M': from = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate()); break
    case 'YTD': from = new Date(now.getFullYear(), 0, 1); break
    case '1Y': from = new Date(now.getFullYear() - 1, now.getMonth(), now.getDate()); break
    default: return data
  }
  return data.filter(p => new Date(p.date) >= from)
}

function getXAxisFormatter(range: TimeRange, locale: string) {
  switch (range) {
    case '24H':
      return (value: string) => {
        const d = new Date(value)
        return d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false })
      }
    case '7D':
      return (value: string) => new Date(value).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
    case '1M':
    case '3M':
      return (value: string) => new Date(value).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
    default:
      return (value: string) => new Date(value).toLocaleDateString(locale, { month: 'short' })
  }
}

export function NetWorthChart({ data, intraday = [], range, onRangeChange, showInvested = true, target }: NetWorthChartProps) {
  const { t } = useTranslation()
  const locale = t('common.locale')
  const is24H = range === '24H'
  const showDots = range === '24H' || range === '7D'

  const targetAt = useMemo(() => {
    if (!target) return null
    const startMs = new Date(target.startDate).getTime()
    const endMs = new Date(target.endDate).getTime()
    const span = endMs - startMs
    if (!Number.isFinite(span) || span <= 0) return null
    const slope = (target.endValue - target.startValue) / span
    return (iso: string) => {
      const t = new Date(iso).getTime()
      if (!Number.isFinite(t)) return null
      const clamped = Math.min(Math.max(t, startMs), endMs)
      return target.startValue + slope * (clamped - startMs)
    }
  }, [target])

  const filteredData = useMemo(() => {
    const base = is24H
      ? intraday.map(p => ({
          date: p.timestamp,
          timestamp: p.timestamp,
          total: p.total as number | null,
          invested: p.invested,
        }))
      : filterByRange(data, range).map(p => ({ ...p, total: p.total as number | null }))

    // When a target is set, crop history on the left to the trajectory start
    // (goal createdAt). Balance changes before the goal existed aren't
    // "savings progress" -- showing them would make the chart misleading.
    const cropped = (() => {
      if (!target) return base
      const startMs = new Date(target.startDate).getTime()
      if (!Number.isFinite(startMs)) return base
      return base.filter(p => new Date(p.date).getTime() >= startMs)
    })()

    // Decorate each visible point with the interpolated target value.
    const decorated = targetAt
      ? cropped.map(p => ({ ...p, target: targetAt(p.date) ?? undefined }))
      : cropped

    // On the ALL range, project the X axis up to the deadline so the user sees
    // the full trajectory. The synthetic point carries only `target` -- the
    // actual line stops where real data ends.
    if (target && range === 'ALL' && targetAt) {
      const last = decorated[decorated.length - 1]
      const deadlineMs = new Date(target.endDate).getTime()
      const lastMs = last ? new Date(last.date).getTime() : -Infinity
      if (Number.isFinite(deadlineMs) && deadlineMs > lastMs) {
        decorated.push({
          date: target.endDate,
          total: null,
          invested: undefined,
          target: target.endValue,
        } as typeof decorated[number])
      }
    }
    return decorated
  }, [data, intraday, range, is24H, target, targetAt])

  const xInterval = useMemo(() => {
    const len = filteredData.length
    if (len <= 8) return 0
    return Math.floor(len / 6)
  }, [filteredData.length])

  const yTickFormatter = useMemo(() => {
    const totals: number[] = []
    for (const d of filteredData) {
      if (d.total != null) totals.push(d.total)
      if ('target' in d && typeof d.target === 'number') totals.push(d.target)
    }
    const maxVal = totals.length ? Math.max(...totals) : 0
    if (maxVal >= 1_000_000) return (v: number) => `${(v / 1_000_000).toFixed(1)}M`
    if (maxVal >= 100_000) return (v: number) => `${(v / 1_000).toFixed(0)}k`
    if (maxVal >= 10_000) return (v: number) => `${(v / 1_000).toFixed(1)}k`
    if (maxVal >= 1_000) return (v: number) => `${(v / 1_000).toFixed(2)}k`
    if (maxVal >= 100) return (v: number) => v.toFixed(0)
    return (v: number) => v.toFixed(2)
  }, [filteredData])

  const chartConfig = useMemo(() => ({
    total: {
      label: t('dashboard.netWorth'),
      color: 'var(--chart-1)',
    },
    invested: {
      label: t('dashboard.invested'),
      color: 'var(--chart-5)',
    },
    target: {
      label: target?.label ?? '',
      color: 'var(--chart-3)',
    },
  }) satisfies ChartConfig, [t, target?.label])

  const labels = useMemo(() => ({
    total: t('dashboard.netWorth'),
    invested: t('dashboard.invested'),
    target: target?.label ?? '',
    gainLoss: t('dashboard.gainLoss'),
    locale: t('common.locale'),
    currency: t('common.currency'),
  }), [t, target?.label])

  const xAxisFormatter = useMemo(() => getXAxisFormatter(range, locale), [range, locale])

  const isEmpty24H = is24H && filteredData.length === 0

  // The dashed "Capital invested" line is only drawn when the caller opts in
  // AND the data actually carries an `invested` value -- prevents legend lies.
  const hasInvestedSeries = showInvested && filteredData.some(d => d.invested != null)
  const hasTargetSeries = target != null && filteredData.some(d => 'target' in d && typeof d.target === 'number')

  return (
    <div>
      <div className="flex justify-end mb-3">
        <TimeRangeSelector value={range} onChange={onRangeChange} />
      </div>
      {isEmpty24H ? (
        <EmptyChartState />
      ) : (
      <ChartContainer config={chartConfig} className="h-[250px] w-full [&>div>div]:!w-full [&>div>div>svg]:!w-full">
        <AreaChart data={filteredData} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="fillTotal" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="var(--color-total)" stopOpacity={0.3} />
            <stop offset="95%" stopColor="var(--color-total)" stopOpacity={0.05} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey={is24H ? 'timestamp' : 'date'}
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={xAxisFormatter}
          interval={xInterval}
        />
        <YAxis
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={yTickFormatter}
          width={45}
          tickCount={5}
        />
        <ChartTooltip content={<NetWorthTooltip labels={labels} is24H={is24H} />} />
        <Area
          dataKey="total"
          type="monotone"
          fill="url(#fillTotal)"
          stroke="var(--color-total)"
          strokeWidth={2}
          dot={showDots ? { r: 3, fill: 'var(--color-total)', strokeWidth: 0 } : false}
          activeDot={{ r: 4 }}
        />
        {hasInvestedSeries && (
          <Line
            dataKey="invested"
            type="monotone"
            stroke="var(--color-invested)"
            strokeWidth={2}
            strokeDasharray="6 4"
            dot={false}
            activeDot={false}
          />
        )}
        {hasTargetSeries && (
          <Line
            dataKey="target"
            type="linear"
            stroke="var(--color-target)"
            strokeWidth={2}
            strokeDasharray="4 4"
            dot={false}
            activeDot={false}
            connectNulls
          />
        )}
        <Legend content={() => (
          <div className="flex flex-wrap items-center justify-center gap-x-5 gap-y-1 pt-2">
            <div className="flex items-center gap-1.5 text-xs">
              <div
                className="h-0.5 w-4 rounded-full"
                style={{ backgroundColor: 'var(--color-total)' }}
              />
              <span className="text-muted-foreground">{labels.total}</span>
            </div>
            {hasInvestedSeries && (
              <div className="flex items-center gap-1.5 text-xs">
                <div
                  className="h-0.5 w-4 border-t-2 border-dashed"
                  style={{ borderColor: 'var(--color-invested)' }}
                />
                <span className="text-muted-foreground">{labels.invested}</span>
              </div>
            )}
            {hasTargetSeries && (
              <div className="flex items-center gap-1.5 text-xs">
                <div
                  className="h-0.5 w-4 border-t-2 border-dashed"
                  style={{ borderColor: 'var(--color-target)' }}
                />
                <span className="text-muted-foreground">{labels.target}</span>
              </div>
            )}
          </div>
        )} />
      </AreaChart>
    </ChartContainer>
      )}
    </div>
  )
}
