import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  useAccount, useAccountHistory, useHoldingsWithLivePrices,
  useAccountTransactions, useAddSnapshot
} from '@/features/accounts/hooks'
import { BalanceHistoryChart } from '@/components/shared/BalanceHistoryChart'
import { HoldingsTable } from '@/components/shared/HoldingsTable'
import { TransactionsList } from '@/components/shared/TransactionsList'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AccountTypeBadge } from '@/components/shared/AccountTypeBadge'
import { PageHeader } from '@/components/shared/PageHeader'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter
} from '@/components/ui/dialog'
import { HugeiconsIcon } from '@hugeicons/react'
import { ArrowLeft01Icon, Calendar03Icon, Loading01Icon } from '@hugeicons/core-free-icons'
import { formatLocalDate, accountTypeLabel } from '@/lib/utils'
import type { BalanceSnapshot } from '@/types/api'

const HOLDING_ACCOUNT_TYPES = ['PEA', 'COMPTE_TITRES', 'CRYPTO']

function getLast12Months() {
  const months = []
  const now = new Date()
  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const year = d.getFullYear()
    const month = d.getMonth() + 1
    const key = `${year}-${String(month).padStart(2, '0')}`
    const lastDay = new Date(year, month, 0).getDate()
    const date = `${key}-${String(lastDay).padStart(2, '0')}`
    const label = d.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
    months.push({ key, year, month, date, label })
  }
  return months
}

function snapshotForMonth(history: BalanceSnapshot[] | undefined, year: number, month: number) {
  return history
    ?.filter(s => {
      const [y, m] = s.date.split('-').map(Number)
      return y === year && m === month
    })
    .sort((a, b) => b.date.localeCompare(a.date))[0]
}

export function AccountDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
  const accountId = parseInt(id!, 10)

  const { data: account, isLoading } = useAccount(accountId)
  const { data: history } = useAccountHistory(accountId)
  const { data: holdings } = useHoldingsWithLivePrices(accountId)
  const { data: transactions } = useAccountTransactions(accountId)
  const addSnapshot = useAddSnapshot()

  const [showHistory, setShowHistory] = useState(false)
  const [values, setValues] = useState<Record<string, string>>({})
  const [modified, setModified] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)

  const months = useMemo(() => getLast12Months(), [])

  const openHistory = () => {
    const initial: Record<string, string> = {}
    months.forEach(({ key, year, month }) => {
      const snap = snapshotForMonth(history, year, month)
      if (snap) initial[key] = String(snap.balance)
    })
    setValues(initial)
    setModified(new Set())
    setShowHistory(true)
  }

  const closeHistory = () => {
    setShowHistory(false)
    setModified(new Set())
  }

  const handleChange = (key: string, value: string) => {
    setValues(prev => ({ ...prev, [key]: value }))
    setModified(prev => new Set([...prev, key]))
  }

  const handleSaveHistory = async () => {
    setSaving(true)
    const toSave = months.filter(({ key }) => modified.has(key) && values[key] !== '')
    await Promise.all(
      toSave.map(({ key, date, year, month }) => {
        const existing = snapshotForMonth(history, year, month)
        const saveDate = existing ? existing.date : date
        return addSnapshot.mutateAsync({ id: accountId, balance: parseFloat(values[key]), date: saveDate })
      })
    )
    setSaving(false)
    closeHistory()
  }

  if (!account && !isLoading) return null

  const chartData = (history ?? []).map(s => ({ date: s.date, balance: s.balance }))
  const showHoldings = account ? HOLDING_ACCOUNT_TYPES.includes(account.type) : false
  const recentSnapshots = [...(history ?? [])].reverse().slice(0, 10)

  return (
    <div className="space-y-4">
      <PageHeader
        surtitle={
          account
            ? `${accountTypeLabel(account.type)}${account.provider ? ` · ${account.provider}` : ''}`
            : undefined
        }
        title={account?.name ?? ''}
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate('/accounts')}
            >
              <HugeiconsIcon icon={ArrowLeft01Icon} size={14} className="mr-1.5" />
              {t('common.back')}
            </Button>
            <Button
              size="sm"
              onClick={openHistory}
            >
              <HugeiconsIcon icon={Calendar03Icon} size={14} className="mr-1.5" />
              {t('accounts.snapshots')}
            </Button>
          </div>
        }
      />

      {/* Balance card */}
      {isLoading && !account ? (
        <Card>
          <CardContent className="pt-6">
            <Skeleton className="h-8 w-48 mb-2" />
            <Skeleton className="h-5 w-32" />
          </CardContent>
        </Card>
      ) : account ? (
        <Card>
          <CardContent className="pt-6">
            <div className="flex items-center gap-3 mb-1">
              <div
                className="w-10 h-10 rounded-xl flex items-center justify-center"
                style={{ backgroundColor: account.color + '20' }}
              >
                <div className="w-4 h-4 rounded-full" style={{ backgroundColor: account.color }} />
              </div>
              <div>
                <div className="flex items-center gap-2 mb-0.5">
                  <span className="text-sm text-muted-foreground font-medium">{account.name}</span>
                  <AccountTypeBadge type={account.type} />
                </div>
                <p className="text-xs text-muted-foreground mb-1">{t('accounts.currentBalance')}</p>
                <CurrencyDisplay
                  value={account.currentBalanceEur}
                  className="text-3xl font-bold text-foreground"
                />
                {account.currency !== 'EUR' && (
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {account.currentBalance} {account.currency}
                    {account.ticker ? ` (${account.ticker})` : ''}
                  </p>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      ) : null}

      {/* History chart */}
      {chartData.length > 1 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('accounts.history')}</CardTitle>
          </CardHeader>
          <CardContent>
            <BalanceHistoryChart data={chartData} />
          </CardContent>
        </Card>
      )}

      {/* Holdings */}
      {showHoldings && (
        holdings ? (
          <HoldingsTable holdings={holdings} />
        ) : (
          <Card>
            <CardContent className="pt-6">
              <Skeleton className="h-32 w-full" />
            </CardContent>
          </Card>
        )
      )}

      {/* Transactions */}
      {transactions ? (
        <TransactionsList transactions={transactions} />
      ) : (
        <Card>
          <CardContent className="pt-6">
            <Skeleton className="h-32 w-full" />
          </CardContent>
        </Card>
      )}

      {/* Snapshot list */}
      {recentSnapshots.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('accounts.snapshots')}</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {recentSnapshots.map(snap => (
              <div
                key={snap.id}
                className="flex items-center justify-between px-6 py-3 border-b last:border-0"
              >
                <span className="text-sm text-muted-foreground">
                  {formatLocalDate(snap.date)}
                </span>
                <CurrencyDisplay value={snap.balance} className="text-sm font-semibold" />
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* Monthly history dialog */}
      <Dialog open={showHistory} onOpenChange={open => { if (!open) closeHistory() }}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{t('accounts.monthlyHistory')}</DialogTitle>
          </DialogHeader>

          <div className="flex flex-col gap-0 max-h-[60vh] overflow-y-auto -mx-1 px-1">
            {months.map(({ key, label }) => {
              const isModified = modified.has(key)
              return (
                <div
                  key={key}
                  className="flex items-center gap-3 py-2 border-b last:border-0"
                >
                  <Label
                    htmlFor={`month-${key}`}
                    className={`flex-1 capitalize text-xs ${isModified ? 'text-foreground font-semibold' : 'text-muted-foreground font-medium'}`}
                  >
                    {label}
                  </Label>
                  <Input
                    id={`month-${key}`}
                    type="number"
                    step="any"
                    min="0"
                    value={values[key] ?? ''}
                    onChange={e => handleChange(key, e.target.value)}
                    placeholder="—"
                    className="w-28 h-7 text-right text-xs"
                  />
                </div>
              )
            })}
          </div>

          <DialogFooter className="gap-2">
            <Button variant="outline" onClick={closeHistory} type="button">
              {t('common.cancel')}
            </Button>
            <Button
              onClick={handleSaveHistory}
              disabled={saving || modified.size === 0}
            >
              {saving && (
                <HugeiconsIcon icon={Loading01Icon} size={12} className="mr-1.5 animate-spin" />
              )}
              {t('accounts.addSnapshot')}
              {modified.size > 0 && ` (${modified.size})`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
