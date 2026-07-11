import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'
import { CheckCircle2, HelpCircle, Sparkles, XCircle } from 'lucide-react'
import type { AssetStatus } from '@/types/api'

/**
 * Resolution status of a holding's underlying asset in the `financial_asset` registry — the
 * at-a-glance signal for the standing mapping/verification flow (D2). `PENDING` (unpriced, needs the
 * operator to link a coin) is the one that demands attention, so it's the only amber/attention badge;
 * `AUTO` is a subtle "verify me" guess, `USER` a confirmed link, `WORTHLESS` a deliberate zero.
 *
 * Renders nothing for a settled `AUTO`/`USER` when `subtle` and the status is trustworthy would be
 * over-engineering — instead the caller decides where to show it. Returns null for a null status.
 */
export function AssetStatusBadge({ status, className }: { status: AssetStatus | null; className?: string }) {
  const { t } = useTranslation()
  if (!status) return null

  const map = {
    PENDING: {
      icon: HelpCircle,
      label: t('assets.status.pending'),
      cls: 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
    },
    AUTO: {
      icon: Sparkles,
      label: t('assets.status.auto'),
      cls: 'bg-sky-500/10 text-sky-600 dark:text-sky-400',
    },
    USER: {
      icon: CheckCircle2,
      label: t('assets.status.user'),
      cls: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
    },
    WORTHLESS: {
      icon: XCircle,
      label: t('assets.status.worthless'),
      cls: 'bg-muted text-muted-foreground',
    },
  }[status]

  const Icon = map.icon
  return (
    <Badge variant="ghost" className={`${map.cls} gap-1 ${className ?? ''}`}>
      <Icon className="size-2.5" />
      {map.label}
    </Badge>
  )
}
