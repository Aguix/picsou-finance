import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

export type TimeRange = '24H' | '7D' | '1M' | '3M' | 'YTD' | '1Y' | 'ALL'

interface TimeRangeSelectorProps {
  value: TimeRange
  onChange: (range: TimeRange) => void
}

const RANGES: TimeRange[] = ['24H', '7D', '1M', '3M', 'YTD', '1Y', 'ALL']

export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  const { t } = useTranslation()

  return (
    <div className="flex flex-wrap items-center gap-1">
      {RANGES.map(range => (
        <button
          key={range}
          onClick={() => onChange(range)}
          className={cn(
            'inline-flex items-center justify-center rounded-md px-1.5 py-1 text-[11px] font-medium transition-colors sm:px-2.5 sm:text-xs',
            value === range
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground'
          )}
        >
          {t(`dashboard.ranges.${range}`)}
        </button>
      ))}
    </div>
  )
}
