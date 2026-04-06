import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAccounts, useCreateAccount, useUpdateAccount, useDeleteAccount } from '@/features/accounts/hooks'
import { AccountForm } from '@/components/shared/AccountForm'
import { AccountCard } from '@/components/shared/AccountCard'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { HugeiconsIcon } from '@hugeicons/react'
import { Add01Icon, Wallet01Icon, Edit02Icon, Delete02Icon } from '@hugeicons/core-free-icons'
import type { Account, AccountRequest } from '@/types/api'

type AccountFormData = {
  name: string
  type: 'LEP' | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS' | 'OTHER'
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color: string
  ticker?: string
}

export function AccountsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const { data: accounts, isLoading } = useAccounts()
  const createAccount = useCreateAccount()
  const updateAccount = useUpdateAccount()
  const deleteAccount = useDeleteAccount()

  const [showForm, setShowForm] = useState(false)
  const [editingAccount, setEditingAccount] = useState<Account | null>(null)
  const [deleteId, setDeleteId] = useState<number | null>(null)

  function handleOpenCreate() {
    setEditingAccount(null)
    setShowForm(true)
  }

  function handleOpenEdit(account: Account) {
    setEditingAccount(account)
    setShowForm(true)
  }

  function handleFormOpenChange(open: boolean) {
    setShowForm(open)
    if (!open) setEditingAccount(null)
  }

  async function handleSubmit(data: AccountFormData) {
    const request: AccountRequest = {
      name: data.name,
      type: data.type,
      provider: data.provider || undefined,
      currency: data.currency,
      currentBalance: data.currentBalance,
      isManual: data.isManual,
      color: data.color,
      ticker: data.ticker || undefined,
    }

    if (editingAccount) {
      await updateAccount.mutateAsync({ id: editingAccount.id, data: request })
    } else {
      await createAccount.mutateAsync(request)
    }

    setShowForm(false)
    setEditingAccount(null)
  }

  async function handleConfirmDelete() {
    if (deleteId === null) return
    await deleteAccount.mutateAsync(deleteId)
    setDeleteId(null)
  }

  const defaultValues: Partial<AccountFormData> | undefined = editingAccount
    ? {
        name: editingAccount.name,
        type: editingAccount.type,
        provider: editingAccount.provider ?? '',
        currency: editingAccount.currency,
        currentBalance: editingAccount.currentBalance,
        isManual: editingAccount.isManual,
        color: editingAccount.color,
        ticker: editingAccount.ticker ?? '',
      }
    : undefined

  const isMutating = createAccount.isPending || updateAccount.isPending

  return (
    <div>
      <PageHeader
        title={t('accounts.title')}
        actions={
          <Button onClick={handleOpenCreate} size="sm">
            <HugeiconsIcon icon={Add01Icon} className="size-4" />
            {t('accounts.addAccount')}
          </Button>
        }
      />

      {isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full rounded-xl" />
          ))}
        </div>
      ) : (accounts ?? []).length === 0 ? (
        <EmptyState
          icon={<HugeiconsIcon icon={Wallet01Icon} strokeWidth={2} className="size-12" />}
          title={t('accounts.noAccounts')}
          action={{ label: t('accounts.addAccount'), onClick: handleOpenCreate }}
        />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {(accounts ?? []).map((account) => (
            <div key={account.id} className="relative group">
              <AccountCard
                account={account}
                onClick={() => navigate(`/accounts/${account.id}`)}
              />
              <div className="absolute top-2 right-2 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-7"
                  onClick={(e) => {
                    e.stopPropagation()
                    handleOpenEdit(account)
                  }}
                >
                  <HugeiconsIcon icon={Edit02Icon} className="size-3.5" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-7 text-destructive hover:text-destructive"
                  onClick={(e) => {
                    e.stopPropagation()
                    setDeleteId(account.id)
                  }}
                >
                  <HugeiconsIcon icon={Delete02Icon} className="size-3.5" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      <AccountForm
        open={showForm}
        onOpenChange={handleFormOpenChange}
        onSubmit={handleSubmit}
        defaultValues={defaultValues}
        title={editingAccount ? t('accounts.editAccount') : t('accounts.addAccount')}
        loading={isMutating}
      />

      <ConfirmDialog
        open={deleteId !== null}
        onOpenChange={(open) => { if (!open) setDeleteId(null) }}
        title={t('accounts.deleteAccount')}
        description={t('accounts.deleteConfirm')}
        onConfirm={handleConfirmDelete}
        loading={deleteAccount.isPending}
        variant="destructive"
      />
    </div>
  )
}
