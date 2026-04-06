import { useTranslation } from 'react-i18next'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatTimeAgo } from '@/lib/utils'

interface PriceFreshnessDotProps {
  priceUpdatedAt: string | null
}

const LIVE_THRESHOLD_MS = 2 * 60 * 1000 // 2 minutes

export function PriceFreshnessDot({ priceUpdatedAt }: PriceFreshnessDotProps) {
  const { t, i18n } = useTranslation()

  if (!priceUpdatedAt) return null

  const age = Date.now() - new Date(priceUpdatedAt).getTime()
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

  const locale = i18n.language.startsWith('fr') ? 'fr-FR' : 'en-US'
  const timeAgo = formatTimeAgo(priceUpdatedAt, locale)

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
