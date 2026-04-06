import { useTranslation } from 'react-i18next'
import type { Account } from '@/types/api'
import { Card, CardContent } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { formatDate } from '@/lib/utils'

interface AccountCardProps {
  account: Account
  onClick?: () => void
}

export function AccountCard({ account, onClick }: AccountCardProps) {
  const { t } = useTranslation()

  return (
    <Card
      className="cursor-pointer transition-shadow hover:shadow-md"
      onClick={onClick}
    >
      <CardContent className="flex items-start gap-3 p-4">
        <div
          className="mt-1 h-10 w-1 shrink-0 rounded-full"
          style={{ backgroundColor: account.color }}
        />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium">{account.name}</span>
            <AccountTypeBadge type={account.type} />
          </div>
          {account.provider && (
            <p className="text-xs text-muted-foreground">{account.provider}</p>
          )}
          <div className="mt-2">
            <CurrencyDisplay
              value={account.currentBalanceEur}
              currency={account.currency}
              className="text-lg font-semibold"
            />
          </div>
          {account.lastSyncedAt && (
            <p className="mt-1 text-xs text-muted-foreground">
              {t('accounts.lastSync')}: {formatDate(account.lastSyncedAt)}
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
