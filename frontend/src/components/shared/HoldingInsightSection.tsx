import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Loader2 } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import PartitionBar, {
  PartitionBarSegment,
  PartitionBarSegmentTitle,
  PartitionBarSegmentValue,
} from '@/components/ui/partition-bar'
import { useSecurityInsight } from '@/features/accounts/hooks'
import type { WeightedSlice } from '@/types/api'
import { formatDate } from '@/lib/utils'

// Cycle through variants so adjacent segments are visually distinct.
const SEGMENT_VARIANTS = ['default', 'secondary', 'outline', 'muted'] as const

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
          <CompositionBar title={t('holdings.insight.countries')} slices={composition.countries} t={t} />
          <CompositionBar title={t('holdings.insight.sectors')} slices={composition.sectors} t={t} />
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
}

function CompositionBar({ title, slices, t }: CompositionBarProps) {
  if (!slices || slices.length === 0) return null

  const sum = slices.reduce((acc, s) => acc + s.percent, 0)
  const others = Math.max(0, Math.round((100 - sum) * 10) / 10)

  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground">{title}</p>
      <PartitionBar size="md" gap={1}>
        {slices.map((slice, i) => (
          <PartitionBarSegment
            key={`${slice.label}-${i}`}
            num={slice.percent}
            variant={SEGMENT_VARIANTS[i % SEGMENT_VARIANTS.length]}
            alignment="left"
          >
            <PartitionBarSegmentTitle>{slice.label}</PartitionBarSegmentTitle>
            <PartitionBarSegmentValue>{slice.percent.toFixed(1)}%</PartitionBarSegmentValue>
          </PartitionBarSegment>
        ))}
        {others > 0.5 && (
          <PartitionBarSegment num={others} variant="muted" alignment="left">
            <PartitionBarSegmentTitle>{t('holdings.insight.others')}</PartitionBarSegmentTitle>
            <PartitionBarSegmentValue>{others.toFixed(1)}%</PartitionBarSegmentValue>
          </PartitionBarSegment>
        )}
      </PartitionBar>
    </div>
  )
}
