import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatTimeAgo } from '@/lib/utils'

interface PriceFreshnessDotProps {
  priceUpdatedAt: string | null
}

const LIVE_THRESHOLD_MS = 2 * 60 * 1000 // 2 minutes

export function PriceFreshnessDot({ priceUpdatedAt }: PriceFreshnessDotProps) {
  const { t } = useTranslation()
  // Snapshot "now" once at mount — freshness doesn't need to tick live, and
  // reading Date.now() during render is an impure call the compiler rejects.
  const [now] = useState(() => Date.now())

  if (!priceUpdatedAt) return null

  const age = now - new Date(priceUpdatedAt).getTime()
  const isLive = age < LIVE_THRESHOLD_MS

  if (isLive) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="size-1.5 shrink-0 rounded-full bg-emerald-500" />
          </TooltipTrigger>
          <TooltipContent>{t('accounts.priceLive')}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    )
  }

  const timeAgo = formatTimeAgo(priceUpdatedAt)

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="size-1.5 shrink-0 rounded-full bg-amber-500" />
        </TooltipTrigger>
        <TooltipContent>{t('accounts.priceStale', { time: timeAgo })}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
