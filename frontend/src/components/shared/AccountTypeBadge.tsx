import { useTranslation } from 'react-i18next'
import { Badge } from '@/components/ui/badge'
import type { AccountType } from '@/types/api'

interface AccountTypeBadgeProps {
  type: AccountType
  className?: string
}

export function AccountTypeBadge({ type, className }: AccountTypeBadgeProps) {
  const { t } = useTranslation()
  return (
    <Badge variant="secondary" className={className}>
      {t(`accountTypes.${type === 'COMPTE_TITRES' ? 'compteTitres' : type.toLowerCase()}`)}
    </Badge>
  )
}
