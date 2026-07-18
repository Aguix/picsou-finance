import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Label,
  Line, Pie, PieChart, Scatter, XAxis, YAxis,
} from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { TrendingUp, TrendingDown, Gift, ChevronDown, ChevronUp, Percent } from 'lucide-react'
import { useCryptoStats } from '@/features/crypto/hooks'
import { REWARD_KIND_LABELS, REWARD_KIND_COLORS } from '@/features/crypto/labels'
import type { CryptoAssetStat, CryptoStatsResponse, RewardKind } from '@/types/api'

const chartConfig = {
  quantity: { label: 'Quantité', color: 'var(--chart-2)' },
  value: { label: 'Valeur', color: 'var(--chart-1)' },
  avgCost: { label: 'Coût moyen', color: 'var(--chart-3)' },
  price: { label: 'Prix du marché', color: 'var(--chart-1)' },
} satisfies ChartConfig

/** Stable slice colour per token in the distribution donut, cycling the theme's chart palette. */
const TOKEN_COLORS = ['var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)']

const fmtQty = (n: number) =>
  new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 8 }).format(n)

const fmtEur = (n: number) =>
  new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(n)

const fmtPct = (n: number) =>
  `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n)} %`

/**
 * A portfolio share as a percentage with decimals, so small holdings read as e.g. "0,3 %" instead of
 * being rounded down to "0 %". Anything non-zero but below 0,01 % is shown as "<0,01 %" rather than a
 * misleading "0".
 */
const fmtSharePct = (n: number) =>
  n > 0 && n < 0.01
    ? '<0,01 %'
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n)} %`

// Chart Y-axis ticks are right-anchored, so a too-wide label (e.g. "84 500,00 €") gets clipped on the
// left and reads as "0". These compact formatters keep axis labels short (k/M suffixes) so large
// values stay legible on a narrow axis; tooltips/legends still use the full-precision formatters.
const fmtAxisEur = (n: number) => {
  const abs = Math.abs(n)
  if (abs >= 1000) return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(n / 1000)} k€`
  return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(n)} €`
}

const fmtAxisQty = (n: number) => {
  const abs = Math.abs(n)
  if (abs >= 1_000_000) return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(n / 1_000_000)} M`
  if (abs >= 1000) return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(n / 1000)} k`
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: abs > 0 && abs < 1 ? 4 : 2 }).format(n)
}

/** Account-bound stats (one exchange/wallet account), rendered on the account detail page. */
export function CryptoStatsSection({ accountId }: { accountId: number }) {
  const { data, isLoading } = useCryptoStats(accountId)
  return <CryptoStatsView data={data} isLoading={isLoading} />
}

/**
 * Presentational stats view, source-agnostic: renders totals + a per-token distribution donut +
 * a card per coin from a {@link CryptoStatsResponse}. Used both for a single account
 * ({@link CryptoStatsSection}) and for the consolidated view pooling every coin across all
 * platforms & wallets (a custom `title`).
 */
export function CryptoStatsView({
  data,
  isLoading,
  title,
}: {
  data: CryptoStatsResponse | undefined
  isLoading: boolean
  title?: string
}) {
  const { t } = useTranslation()

  if (isLoading) {
    return (
      <Card>
        <CardContent className="pt-6">
          <Skeleton className="h-40 w-full" />
        </CardContent>
      </Card>
    )
  }
  if (!data || data.assets.length === 0) return null

  const rewardBars = (Object.entries(data.totals.rewardsByKindEur) as [RewardKind, number][])
    .filter(([, v]) => v > 0)
    .sort((a, b) => b[1] - a[1])
    .map(([kind, value]) => ({
      kind,
      label: REWARD_KIND_LABELS[kind] ?? kind,
      value,
      fill: REWARD_KIND_COLORS[kind] ?? 'var(--chart-1)',
    }))

  return (
    <div className="space-y-4">
      {/* Totals */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{title ?? t('cryptoStats.title', 'Statistiques crypto')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Metric label={t('cryptoStats.invested', 'Investi')} value={<CurrencyDisplay value={data.totals.totalInvestedEur} />} />
            <Metric label={t('cryptoStats.value', 'Valeur actuelle')} value={<CurrencyDisplay value={data.totals.currentValueEur} />} />
            <Metric
              label={t('cryptoStats.rewards', 'Récompenses')}
              value={<CurrencyDisplay value={data.totals.totalRewardsEur} className="text-amber-600" />}
            />
            <Metric
              label={t('cryptoStats.yield', 'Rendement récompenses')}
              value={
                data.totals.rewardsYieldPct != null
                  ? <span className="font-semibold text-amber-600">{fmtPct(data.totals.rewardsYieldPct)}</span>
                  : <span>—</span>
              }
            />
          </div>

          <PerTokenDonut assets={data.assets} />

          {rewardBars.length > 0 && (
            <div>
              <p className="mb-2 flex items-center gap-1.5 text-xs text-muted-foreground">
                <Gift className="size-3.5 text-amber-500" />
                {t('cryptoStats.rewardsByProgram', 'Gains par programme')}
              </p>
              <ChartContainer config={chartConfig} className="h-[180px] w-full">
                <BarChart data={rewardBars} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={8} />
                  <YAxis tickLine={false} axisLine={false} width={52}
                    tickFormatter={(v) => fmtAxisEur(Number(v))} />
                  <ChartTooltip
                    content={<ChartTooltipContent
                      formatter={(v) => new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(Number(v))}
                    />}
                  />
                  <Bar dataKey="value" radius={4}>
                    {rewardBars.map((b) => <Cell key={b.kind} fill={b.fill} />)}
                  </Bar>
                </BarChart>
              </ChartContainer>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Per-crypto */}
      {data.assets.map((asset) => (
        <AssetCard key={asset.ticker} asset={asset} />
      ))}
    </div>
  )
}

type DonutSlice = { ticker: string; value: number; share: number; fill: string }

/**
 * Per-token distribution donut: how the portfolio's current value splits across coins, built from
 * the same {@link CryptoAssetStat} list the cards render. Complements the per-account allocation
 * pie on the dashboard, which splits by account rather than by coin.
 */
function PerTokenDonut({ assets }: { assets: CryptoAssetStat[] }) {
  const { t } = useTranslation()
  const priced = assets.filter((a) => a.currentValueEur != null && a.currentValueEur > 0)
  const total = priced.reduce((s, a) => s + (a.currentValueEur ?? 0), 0)
  if (priced.length < 2 || total <= 0) return null

  const slices: DonutSlice[] = [...priced]
    .sort((a, b) => (b.currentValueEur ?? 0) - (a.currentValueEur ?? 0))
    .map((a, i) => ({
      ticker: a.ticker,
      value: a.currentValueEur ?? 0,
      share: ((a.currentValueEur ?? 0) / total) * 100,
      fill: TOKEN_COLORS[i % TOKEN_COLORS.length],
    }))

  return (
    <div>
      <p className="mb-2 text-xs text-muted-foreground">
        {t('cryptoStats.distribution', 'Répartition par crypto')}
      </p>
      <div className="flex flex-col items-center gap-3 sm:flex-row">
        <ChartContainer config={chartConfig} className="h-[200px] w-full max-w-[220px] shrink-0">
          <PieChart>
            <ChartTooltip
              content={<ChartTooltipContent
                nameKey="ticker"
                formatter={(v) => fmtEur(Number(v))}
              />}
            />
            <Pie data={slices} dataKey="value" nameKey="ticker" cx="50%" cy="50%"
              innerRadius={55} outerRadius={85} paddingAngle={2} strokeWidth={0}>
              {slices.map((s) => <Cell key={s.ticker} fill={s.fill} />)}
              <Label
                content={({ viewBox }) => {
                  if (viewBox && 'cx' in viewBox && 'cy' in viewBox) {
                    return (
                      <text x={viewBox.cx} y={viewBox.cy} textAnchor="middle" dominantBaseline="middle">
                        <tspan x={viewBox.cx} y={viewBox.cy} className="fill-foreground text-2xl font-bold">
                          {slices.length}
                        </tspan>
                        <tspan x={viewBox.cx} y={(viewBox.cy || 0) + 20} className="fill-muted-foreground text-xs">
                          {t('cryptoStats.coins', 'cryptos')}
                        </tspan>
                      </text>
                    )
                  }
                }}
              />
            </Pie>
          </PieChart>
        </ChartContainer>
        <div className="grid w-full min-w-0 flex-1 grid-cols-2 gap-x-4 gap-y-1">
          {slices.map((s) => (
            <div key={s.ticker} className="flex items-center gap-2 text-sm">
              <div className="size-2.5 rounded-full shrink-0" style={{ backgroundColor: s.fill }} />
              <span className="truncate font-medium">{s.ticker}</span>
              <span className="ml-auto text-muted-foreground">{fmtSharePct(s.share)}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function AssetCard({ asset }: { asset: CryptoAssetStat }) {
  const { t } = useTranslation()
  const [showAccumulation, setShowAccumulation] = useState(false)
  // Cumulative quantity over time, from buys + rewards combined.
  const events = [
    ...asset.buyEvents.map((e) => ({ date: e.date, qty: e.quantity ?? 0 })),
    ...asset.rewardEvents.map((e) => ({ date: e.date, qty: e.quantity ?? 0 })),
  ].sort((a, b) => a.date.localeCompare(b.date))

  // Cumulative quantity at each event (events are few, so the O(n²) scan is fine and avoids
  // a render-time mutable accumulator).
  const series = events.map((e, i) => ({
    date: e.date,
    quantity: events.slice(0, i + 1).reduce((sum, x) => sum + x.qty, 0),
  }))

  // Buy/sell moments, aggregated per day (a day can hold several orders). The marker sits at the
  // weighted execution price — i.e. the coin's EUR value at that moment — and carries the EUR amount
  // and quantity for the tooltip.
  const aggregateByDate = (evts: { date: string; quantity: number | null; valueEur: number }[]) => {
    const m = new Map<string, { amount: number; qty: number }>()
    for (const e of evts) {
      const cur = m.get(e.date) ?? { amount: 0, qty: 0 }
      cur.amount += e.valueEur
      cur.qty += e.quantity ?? 0
      m.set(e.date, cur)
    }
    return m
  }
  const buyByDate = aggregateByDate(asset.buyEvents)
  const sellByDate = aggregateByDate(asset.sellEvents)

  // Average cost (cost basis per unit) over time, overlaid with the coin's market price.
  // The cost is step-wise, so at each date we forward-fill it to its latest value on or before
  // that date (the series are short, so the O(n²) scan is fine and avoids a mutable accumulator).
  const priceByDate = new Map(asset.priceSeries.map((p) => [p.date, p.priceEur]))
  const allDates = Array.from(
    new Set([
      ...asset.costSeries.map((p) => p.date),
      ...asset.priceSeries.map((p) => p.date),
      ...buyByDate.keys(),
      ...sellByDate.keys(),
    ]),
  ).sort()
  const costVsPrice = allDates.map((date) => {
    const price = priceByDate.get(date) ?? null
    const b = buyByDate.get(date)
    const s = sellByDate.get(date)
    // Marker Y = weighted execution price; fall back to the market price line if qty is missing.
    const buyMarker = b ? (b.qty > 0 ? b.amount / b.qty : price) : null
    const sellMarker = s ? (s.qty > 0 ? s.amount / s.qty : price) : null
    return {
      date,
      avgCost: asset.costSeries.filter((p) => p.date <= date).at(-1)?.averageBuyIn ?? null,
      price,
      buyMarker,
      buyAmount: b?.amount ?? null,
      buyQty: b?.qty ?? null,
      sellMarker,
      sellAmount: s?.amount ?? null,
      sellQty: s?.qty ?? null,
    }
  })

  const pnl = asset.unrealizedPnlEur
  const pnlPositive = pnl != null && pnl >= 0
  const rewardKinds = (Object.entries(asset.rewardsByKindEur) as [RewardKind, number][])
    .filter(([, v]) => v > 0)
    .sort((a, b) => b[1] - a[1])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center justify-between gap-2 text-base">
          <span className="flex items-center gap-2">
            <Badge variant="secondary">{asset.ticker}</Badge>
            {asset.name && asset.name !== asset.ticker && (
              <span className="text-sm font-normal text-muted-foreground">{asset.name}</span>
            )}
          </span>
          {asset.currentValueEur != null && (
            <CurrencyDisplay value={asset.currentValueEur} className="text-base" />
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
          <Metric label={t('cryptoStats.quantity', 'Quantité')} value={<span className="font-medium">{fmtQty(asset.quantity)}</span>} />
          <Metric
            label={t('cryptoStats.avgBuyIn', "Prix d'achat moyen")}
            value={asset.averageBuyIn != null ? <CurrencyDisplay value={asset.averageBuyIn} /> : <span>—</span>}
          />
          <Metric
            label={t('cryptoStats.currentPrice', 'Prix actuel')}
            value={asset.currentPrice != null ? <CurrencyDisplay value={asset.currentPrice} /> : <span>—</span>}
          />
          <Metric label={t('cryptoStats.invested', 'Investi')} value={<CurrencyDisplay value={asset.totalInvestedEur} />} />
          <Metric
            label={t('cryptoStats.rewards', 'Récompenses')}
            value={<CurrencyDisplay value={asset.totalRewardsEur} className="text-amber-600" />}
          />
        </div>

        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          {pnl != null && (
            <div className="flex items-center gap-2">
              {pnlPositive ? <TrendingUp className="size-4 text-emerald-500" /> : <TrendingDown className="size-4 text-red-500" />}
              <CurrencyDisplay value={pnl} className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`} />
              <span className="text-sm text-muted-foreground">{t('cryptoStats.unrealized', 'Plus/moins-value latente')}</span>
            </div>
          )}
          {asset.rewardsYieldPct != null && (
            <div className="flex items-center gap-1.5">
              <Percent className="size-4 text-amber-500" />
              <span className="text-sm font-medium text-amber-600">{fmtPct(asset.rewardsYieldPct)}</span>
              <span className="text-sm text-muted-foreground">{t('cryptoStats.yieldShort', 'rendement récompenses')}</span>
            </div>
          )}
        </div>

        {rewardKinds.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {rewardKinds.map(([kind, value]) => (
              <Badge key={kind} variant="outline" className="gap-1">
                {REWARD_KIND_LABELS[kind] ?? kind}
                <CurrencyDisplay value={value} className="font-medium" />
              </Badge>
            ))}
          </div>
        )}

        {costVsPrice.length > 1 && (
          <div>
            <p className="mb-2 text-xs text-muted-foreground">
              {t('cryptoStats.costVsPrice', 'Coût moyen vs prix du marché')}
            </p>
            <ChartContainer config={chartConfig} className="h-[180px] w-full">
              <ComposedChart data={costVsPrice} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="date" tickLine={false} axisLine={false} tickMargin={8}
                  tickFormatter={(v) => new Date(v).toLocaleDateString('fr-FR', { month: 'short', year: '2-digit' })} />
                <YAxis tickLine={false} axisLine={false} width={52}
                  tickFormatter={(v) => fmtAxisEur(Number(v))} />
                <ChartTooltip content={<CostPriceTooltip />} />
                <Line dataKey="price" name="price" type="monotone" stroke="var(--color-price)"
                  strokeWidth={2} dot={false} connectNulls />
                <Line dataKey="avgCost" name="avgCost" type="stepAfter" stroke="var(--color-avgCost)"
                  strokeWidth={2} strokeDasharray="5 4" dot={false} connectNulls />
                {/* Buy (green) / sell (red) markers, placed at the execution price. */}
                <Scatter dataKey="buyMarker" name="buy" fill="#10b981" shape="triangle" isAnimationActive={false} />
                <Scatter dataKey="sellMarker" name="sell" fill="#ef4444" shape="diamond" isAnimationActive={false} />
              </ComposedChart>
            </ChartContainer>
          </div>
        )}

        {series.length > 1 && (
          <div>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7 gap-1 px-2 text-xs text-muted-foreground"
              onClick={() => setShowAccumulation((v) => !v)}
            >
              {showAccumulation ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
              {t('cryptoStats.accumulation', 'Accumulation (achats + récompenses)')}
            </Button>
            {showAccumulation && (
            <ChartContainer config={chartConfig} className="mt-2 h-[160px] w-full">
              <AreaChart data={series} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id={`fillQty-${asset.ticker}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="var(--color-quantity)" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="var(--color-quantity)" stopOpacity={0.05} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="date" tickLine={false} axisLine={false} tickMargin={8}
                  tickFormatter={(v) => new Date(v).toLocaleDateString('fr-FR', { month: 'short', year: '2-digit' })} />
                <YAxis tickLine={false} axisLine={false} width={45}
                  tickFormatter={(v) => fmtAxisQty(Number(v))} />
                <ChartTooltip content={<ChartTooltipContent formatter={(v) => fmtQty(Number(v))} />} />
                <Area dataKey="quantity" type="monotone" stroke="var(--color-quantity)"
                  fill={`url(#fillQty-${asset.ticker})`} strokeWidth={2} />
              </AreaChart>
            </ChartContainer>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

type CostPricePoint = {
  date: string
  avgCost: number | null
  price: number | null
  buyMarker: number | null
  buyAmount: number | null
  buyQty: number | null
  sellMarker: number | null
  sellAmount: number | null
  sellQty: number | null
}

/** Tooltip for the cost-vs-price chart: market price, average cost, and any buy/sell on that day. */
function CostPriceTooltip({
  active,
  payload,
}: {
  active?: boolean
  payload?: Array<{ payload: CostPricePoint }>
}) {
  const { t } = useTranslation()
  if (!active || !payload?.length) return null
  const row = payload[0].payload
  return (
    <div className="rounded-lg border bg-background px-3 py-2 text-xs shadow-md">
      <div className="mb-1 font-medium">
        {new Date(row.date).toLocaleDateString('fr-FR', { day: '2-digit', month: 'short', year: 'numeric' })}
      </div>
      {row.price != null && (
        <div className="flex items-center justify-between gap-4">
          <span className="text-muted-foreground">{t('cryptoStats.marketPrice', 'Prix du marché')}</span>
          <span>{fmtEur(row.price)}</span>
        </div>
      )}
      {row.avgCost != null && (
        <div className="flex items-center justify-between gap-4">
          <span className="text-muted-foreground">{t('cryptoStats.avgCost', 'Coût moyen')}</span>
          <span>{fmtEur(row.avgCost)}</span>
        </div>
      )}
      {row.buyAmount != null && (
        <div className="mt-1 border-t pt-1 text-emerald-600">
          <div className="font-medium">{t('cryptoStats.buy', 'Achat')} · {fmtEur(row.buyAmount)}</div>
          <div className="text-muted-foreground">
            {fmtQty(row.buyQty ?? 0)} @ {row.buyMarker != null ? fmtEur(row.buyMarker) : '—'}
          </div>
        </div>
      )}
      {row.sellAmount != null && (
        <div className="mt-1 border-t pt-1 text-red-600">
          <div className="font-medium">{t('cryptoStats.sell', 'Vente')} · {fmtEur(row.sellAmount)}</div>
          <div className="text-muted-foreground">
            {fmtQty(row.sellQty ?? 0)} @ {row.sellMarker != null ? fmtEur(row.sellMarker) : '—'}
          </div>
        </div>
      )}
    </div>
  )
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-lg bg-muted/40 px-3 py-2">
      <p className="text-xs text-muted-foreground">{label}</p>
      <div className="mt-0.5 font-semibold">{value}</div>
    </div>
  )
}
