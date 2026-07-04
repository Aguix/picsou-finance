import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ComposedChart, Line, Scatter, XAxis, YAxis } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { TrendingUp, TrendingDown, Gift, ChevronDown, ChevronUp } from 'lucide-react'
import { useCryptoStats } from '@/features/crypto/hooks'
import { REWARD_KIND_LABELS, REWARD_KIND_COLORS } from '@/features/crypto/labels'
import type { CryptoAssetStat, CryptoStatsResponse, RewardKind } from '@/types/api'

const chartConfig = {
  quantity: { label: 'Quantité', color: 'var(--chart-2)' },
  value: { label: 'Valeur', color: 'var(--chart-1)' },
  avgCost: { label: 'Coût moyen', color: 'var(--chart-3)' },
  price: { label: 'Prix du marché', color: 'var(--chart-1)' },
} satisfies ChartConfig

const fmtQty = (n: number) =>
  new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 8 }).format(n)

const fmtEur = (n: number) =>
  new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(n)

/** Account-bound stats (one exchange/wallet account), rendered on the account detail page. */
export function CryptoStatsSection({ accountId }: { accountId: number }) {
  const { data, isLoading } = useCryptoStats(accountId)
  return <CryptoStatsView data={data} isLoading={isLoading} />
}

/**
 * Presentational stats view, source-agnostic: renders totals + a card per coin from a
 * {@link CryptoStatsResponse}. Used both for a single account ({@link CryptoStatsSection}) and for
 * the consolidated view pooling every coin across all platforms & wallets (a custom `title`).
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
          <div className="grid grid-cols-3 gap-3">
            <Metric label={t('cryptoStats.invested', 'Investi')} value={<CurrencyDisplay value={data.totals.totalInvestedEur} />} />
            <Metric label={t('cryptoStats.value', 'Valeur actuelle')} value={<CurrencyDisplay value={data.totals.currentValueEur} />} />
            <Metric
              label={t('cryptoStats.rewards', 'Récompenses')}
              value={<CurrencyDisplay value={data.totals.totalRewardsEur} className="text-amber-600" />}
            />
          </div>

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
                  <YAxis tickLine={false} axisLine={false} width={45}
                    tickFormatter={(v) => `${Math.round(Number(v))}`} />
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

        {pnl != null && (
          <div className="flex items-center gap-2">
            {pnlPositive ? <TrendingUp className="size-4 text-emerald-500" /> : <TrendingDown className="size-4 text-red-500" />}
            <CurrencyDisplay value={pnl} className={`text-sm font-medium ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`} />
            <span className="text-sm text-muted-foreground">{t('cryptoStats.unrealized', 'Plus/moins-value latente')}</span>
          </div>
        )}

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
                <YAxis tickLine={false} axisLine={false} width={55}
                  tickFormatter={(v) => fmtEur(Number(v))} />
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
                  tickFormatter={(v) => fmtQty(Number(v))} />
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
