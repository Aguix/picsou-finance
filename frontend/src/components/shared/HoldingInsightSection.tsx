import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Loader2 } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import PartitionBar, { PartitionBarSegment } from '@/components/ui/partition-bar'
import { useSecurityInsight } from '@/features/accounts/hooks'
import type { WeightedSlice } from '@/types/api'
import { cn, formatDate } from '@/lib/utils'

// Solid, theme-stable palette cycled across slices. The bar stays a pure
// proportional visual; the legend below carries the labels, so this works at
// any slice count and reflows cleanly on a phone (no sub-10px truncated text).
const PALETTE = [
  'bg-sky-500',
  'bg-violet-500',
  'bg-emerald-500',
  'bg-amber-500',
  'bg-rose-500',
  'bg-teal-500',
  'bg-indigo-500',
  'bg-fuchsia-500',
  'bg-lime-500',
  'bg-orange-500',
] as const
const OTHERS_COLOR = 'bg-muted-foreground/30'

interface HoldingInsightSectionProps {
  ticker: string | null
  name: string | null
  open: boolean
}

export function HoldingInsightSection({ ticker, name, open }: HoldingInsightSectionProps) {
  const { t } = useTranslation()
  const { data, isLoading } = useSecurityInsight(ticker, name, open)

  if (!ticker) return null

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6 border-t">
        <Loader2 className="size-4 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Demo mode / unknown tickers return an empty object → nothing to show.
  if (!data || !data.assetType) return null

  const composition = data.composition

  return (
    <div className="space-y-4 pt-4 border-t">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t('holdings.insight.title')}</h3>
        <Badge variant="outline">{t(`holdings.insight.assetTypes.${data.assetType}`)}</Badge>
      </div>

      {composition ? (
        <div className="space-y-4">
          <CompositionBar title={t('holdings.insight.companies')} slices={composition.companies} t={t} />
          <CompositionBar title={t('holdings.insight.countries')} slices={composition.countries} t={t} labelNs="holdings.insight.countryNames" />
          <CompositionBar title={t('holdings.insight.sectors')} slices={composition.sectors} t={t} labelNs="holdings.insight.sectorNames" />
          {(composition.source || composition.asOf) && (
            <p className="text-[10px] text-muted-foreground">
              {composition.source && t('holdings.insight.source', { source: composition.source })}
              {composition.source && composition.asOf && ' · '}
              {composition.asOf && t('holdings.insight.asOf', { date: formatDate(composition.asOf) })}
            </p>
          )}
        </div>
      ) : data.assetType === 'ETF' ? (
        <p className="text-xs text-muted-foreground">{t('holdings.insight.unavailable')}</p>
      ) : null}
    </div>
  )
}

interface CompositionBarProps {
  title: string
  slices: WeightedSlice[]
  t: TFunction
  labelNs?: string
}

function CompositionBar({ title, slices, t, labelNs }: CompositionBarProps) {
  if (!slices || slices.length === 0) return null

  const labelOf = (raw: string) => (labelNs ? t(`${labelNs}.${raw}`, raw) : raw)
  const sum = slices.reduce((acc, s) => acc + s.percent, 0)
  const others = Math.max(0, Math.round((100 - sum) * 10) / 10)

  // One entry per legend item; the bar and the legend share the same list so
  // colours line up. "Others" is only meaningful when the top-N undershoots 100%.
  const items: { label: string; percent: number; color: string }[] = slices.map((slice, i) => ({
    label: labelOf(slice.label),
    percent: slice.percent,
    color: PALETTE[i % PALETTE.length],
  }))
  if (others > 0.5) {
    items.push({ label: t('holdings.insight.others'), percent: others, color: OTHERS_COLOR })
  }

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium text-muted-foreground">{title}</p>

      {/* Proportional bar — colour only, stays legible at any width. */}
      <PartitionBar size="sm" gap={0.5} className="min-h-0">
        {items.map((item, i) => (
          <PartitionBarSegment
            key={`${item.label}-${i}`}
            num={item.percent}
            className={cn('min-h-2.5 rounded-sm px-0 py-0', item.color)}
          />
        ))}
      </PartitionBar>

      {/* Legend — wraps to as many columns as fit, so it reads on a phone. */}
      <ul className="flex flex-wrap gap-x-3 gap-y-1">
        {items.map((item, i) => (
          <li key={`${item.label}-${i}`} className="flex min-w-0 items-center gap-1.5 text-xs">
            <span className={cn('size-2 shrink-0 rounded-[2px]', item.color)} aria-hidden />
            <span className="truncate text-foreground">{item.label}</span>
            <span className="shrink-0 tabular-nums text-muted-foreground">{item.percent.toFixed(1)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
