import { useState, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import { ACCOUNT_COLORS } from '@/lib/constants'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  Upload,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  X,
} from 'lucide-react'
import type {
  Account,
  FinaryPreviewResponse,
  FinaryAccountMapping,
  FinaryImportResultResponse,
  FinaryMappingAction,
  AccountType,
} from '@/types/api'

type WizardStep = 1 | 2 | 3

interface NewAccountForm {
  name: string
  type: AccountType
  provider: string
  currency: string
  color: string
}

const defaultNewAccount: NewAccountForm = {
  name: '',
  type: 'CHECKING',
  provider: 'Finary',
  currency: 'EUR',
  color: ACCOUNT_COLORS[0],
}

export function FinaryTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const [step, setStep] = useState<WizardStep>(1)
  const [previewData, setPreviewData] = useState<FinaryPreviewResponse | null>(null)
  const [mappings, setMappings] = useState<FinaryAccountMapping[]>([])
  const [importResult, setImportResult] = useState<FinaryImportResultResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // API sync state
  const [totpRequired, setTotpRequired] = useState(false)
  const [totpCode, setTotpCode] = useState('')
  const [isApiSync, setIsApiSync] = useState(false)

  const fileInputRef = useRef<HTMLInputElement>(null)
  const [dragOver, setDragOver] = useState(false)

  // ----- Helpers -----

  function resetWizard() {
    setStep(1)
    setPreviewData(null)
    setMappings([])
    setImportResult(null)
    setError(null)
    setTotpRequired(false)
    setTotpCode('')
    setIsApiSync(false)
  }

  // ----- Step 1: Upload / API Sync -----

  async function handleFileUpload(file: File) {
    setLoading(true)
    setError(null)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const res = await api.post<FinaryPreviewResponse>('/finary/preview', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setPreviewData(res.data)
      initMappings(res.data)
      setIsApiSync(false)
      setStep(2)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : t('common.retry')
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  async function checkApiConfigured() {
    setLoading(true)
    setError(null)
    try {
      const res = await api.get<boolean>('/finary/configured')
      if (res.data) {
        await handleApiSyncPreview()
      } else {
        setError(t('sync.finary.apiNotConfigured'))
      }
    } catch {
      setError(t('common.retry'))
    } finally {
      setLoading(false)
    }
  }

  async function handleApiSyncPreview() {
    setLoading(true)
    setError(null)
    try {
      const params = totpCode ? `?totp=${encodeURIComponent(totpCode)}` : ''
      const res = await api.post<FinaryPreviewResponse>(
        `/finary/api-sync/preview${params}`
      )
      setPreviewData(res.data)
      initMappings(res.data)
      setIsApiSync(true)
      setTotpRequired(false)
      setStep(2)
    } catch (err: unknown) {
      const axiosErr = err as { response?: { status?: number } }
      if (axiosErr.response?.status === 403) {
        setTotpRequired(true)
      } else {
        const msg = err instanceof Error ? err.message : t('common.retry')
        setError(msg)
      }
    } finally {
      setLoading(false)
    }
  }

  function initMappings(preview: FinaryPreviewResponse) {
    const initialMappings: FinaryAccountMapping[] = preview.accounts.map((account) => ({
      finaryName: account.finaryName,
      finaryCategory: account.finaryCategory,
      action: 'CREATE_NEW' as FinaryMappingAction,
      targetAccountId: undefined,
      newAccount: {
        name: account.finaryName,
        type: account.suggestedType,
        provider: account.finaryInstitution,
        currency: account.nativeCurrency,
        color: ACCOUNT_COLORS[preview.accounts.indexOf(account) % ACCOUNT_COLORS.length],
      },
    }))
    setMappings(initialMappings)
  }

  function onFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) handleFileUpload(file)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files?.[0]
    if (file) handleFileUpload(file)
  }

  // ----- Step 2: Mapping -----

  function updateMapping(
    index: number,
    patch: Partial<FinaryAccountMapping>
  ) {
    setMappings((prev) =>
      prev.map((m, i) => (i === index ? { ...m, ...patch } : m))
    )
  }

  function setMappingAction(index: number, action: FinaryMappingAction) {
    const mapping = mappings[index]
    const account = previewData?.accounts[index]

    if (action === 'SKIP') {
      updateMapping(index, { action, targetAccountId: undefined, newAccount: undefined })
    } else if (action === 'MAP_EXISTING') {
      updateMapping(index, { action, newAccount: undefined })
    } else {
      updateMapping(index, {
        action,
        targetAccountId: undefined,
        newAccount: {
          name: mapping.newAccount?.name ?? account?.finaryName ?? '',
          type: mapping.newAccount?.type ?? account?.suggestedType ?? 'OTHER',
          provider: mapping.newAccount?.provider ?? account?.finaryInstitution ?? 'Finary',
          currency: mapping.newAccount?.currency ?? account?.nativeCurrency ?? 'EUR',
          color: mapping.newAccount?.color ?? ACCOUNT_COLORS[0],
        },
      })
    }
  }

  function updateNewAccountField(index: number, field: keyof NewAccountForm, value: string) {
    const current = mappings[index].newAccount ?? { ...defaultNewAccount }
    updateMapping(index, {
      newAccount: { ...current, [field]: value },
    })
  }

  async function handleImport() {
    if (!previewData) return
    setLoading(true)
    setError(null)
    try {
      const token = isApiSync ? previewData.fileToken : previewData.fileToken
      if (isApiSync) {
        const res = await api.post<FinaryImportResultResponse>('/finary/api-sync/execute', {
          syncToken: token,
          mappings,
        })
        setImportResult(res.data)
      } else {
        const res = await api.post<FinaryImportResultResponse>('/finary/import', {
          fileToken: token,
          mappings,
        })
        setImportResult(res.data)
      }
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      setStep(3)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : t('common.retry')
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  // ----- Render -----

  const hasSkipAll = mappings.every((m) => m.action === 'SKIP')

  return (
    <div className="space-y-6">
      {/* Step indicator */}
      <div className="flex items-center justify-center gap-2">
        {([1, 2, 3] as const).map((s) => (
          <div key={s} className="flex items-center gap-2">
            <div
              className={`flex size-8 items-center justify-center rounded-full text-sm font-medium transition-colors ${
                step >= s
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-muted text-muted-foreground'
              }`}
            >
              {step > s ? (
                <CheckCircle2 className="size-4" />
              ) : (
                s
              )}
            </div>
            <span className="text-sm text-muted-foreground">{t(`sync.finary.step${s}`)}</span>
            {s < 3 && (
              <div
                className={`mx-1 h-px w-8 ${
                  step > s ? 'bg-primary' : 'bg-muted'
                }`}
              />
            )}
          </div>
        ))}
      </div>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <X className="size-4 shrink-0" />
          <span className="flex-1">{error}</span>
          <Button variant="ghost" size="icon-xs" onClick={() => setError(null)}>
            <X className="size-3" />
          </Button>
        </div>
      )}

      {/* Step 1: Upload / API Sync */}
      {step === 1 && (
        <div className="mx-auto max-w-lg space-y-4">
          {/* File upload zone */}
          <div
            className={`flex flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed p-12 text-center transition-colors ${
              dragOver
                ? 'border-primary bg-primary/5'
                : 'border-muted-foreground/25 hover:border-muted-foreground/50'
            }`}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
          >
            <Upload className="size-6 text-muted-foreground" />
            <div>
              <p className="font-medium">{t('sync.finary.uploadFile')}</p>
              <p className="mt-1 text-sm text-muted-foreground">{t('sync.finary.uploadHint')}</p>
            </div>
            <Button
              variant="outline"
              onClick={() => fileInputRef.current?.click()}
              disabled={loading}
            >
              <Upload />
              {t('sync.finary.uploadFile')}
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx"
              className="hidden"
              onChange={onFileSelected}
            />
          </div>

          {/* Divider */}
          <div className="flex items-center gap-3">
            <div className="h-px flex-1 bg-border" />
            <span className="text-xs text-muted-foreground">ou</span>
            <div className="h-px flex-1 bg-border" />
          </div>

          {/* API Sync */}
          <div className="space-y-3">
            <Button
              variant="outline"
              className="w-full"
              onClick={checkApiConfigured}
              disabled={loading}
            >
              <Upload />
              {t('sync.finary.apiSync')}
            </Button>

            {totpRequired && (
              <div className="flex gap-2">
                <div className="flex-1">
                  <Label htmlFor="finary-totp">{t('sync.finary.totp')}</Label>
                  <Input
                    id="finary-totp"
                    value={totpCode}
                    onChange={(e) => setTotpCode(e.target.value)}
                    placeholder="000000"
                    maxLength={6}
                    className="mt-1"
                  />
                </div>
                <Button
                  className="mt-6"
                  onClick={handleApiSyncPreview}
                  disabled={totpCode.length !== 6 || loading}
                >
                  {t('sync.finary.next')}
                  <ArrowRight />
                </Button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Step 2: Account Mapping */}
      {step === 2 && previewData && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <Button variant="ghost" size="sm" onClick={() => { setStep(1); setPreviewData(null) }}>
              <ArrowLeft />
              {t('sync.finary.back')}
            </Button>
            <Button onClick={handleImport} disabled={loading || hasSkipAll}>
              {loading ? t('common.loading') : t('sync.finary.import')}
              <ArrowRight />
            </Button>
          </div>

          <div className="space-y-3">
            {previewData.accounts.map((account, index) => (
              <MappingCard
                key={account.finaryName + account.finaryCategory}
                account={account}
                mapping={mappings[index]}
                existingAccounts={previewData.existingPicsouAccounts}
                onActionChange={(action) => setMappingAction(index, action)}
                onTargetChange={(id) => updateMapping(index, { targetAccountId: id })}
                onNewAccountField={(field, value) => updateNewAccountField(index, field, value)}
              />
            ))}
          </div>
        </div>
      )}

      {/* Step 3: Results */}
      {step === 3 && importResult && (
        <div className="mx-auto max-w-lg space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <ResultStat
              label={t('sync.finary.accountsCreated')}
              value={importResult.accountsCreated}
              color="text-emerald-600"
            />
            <ResultStat
              label={t('sync.finary.accountsMapped')}
              value={importResult.accountsMapped}
              color="text-blue-600"
            />
            <ResultStat
              label={t('sync.finary.accountsSkipped')}
              value={importResult.accountsSkipped}
              color="text-muted-foreground"
            />
            <ResultStat
              label={t('sync.finary.transactionsImported')}
              value={importResult.transactionsImported}
              color="text-violet-600"
            />
          </div>

          {importResult.importedAccounts.length > 0 && (
            <Card size="sm">
              <CardContent className="space-y-2 pt-0">
                {importResult.importedAccounts.map((account) => (
                  <div
                    key={account.id}
                    className="flex items-center gap-3 rounded-lg px-3 py-2"
                  >
                    <div
                      className="size-3 shrink-0 rounded-full"
                      style={{ backgroundColor: account.color }}
                    />
                    <div className="flex-1 min-w-0">
                      <p className="truncate text-sm font-medium">{account.name}</p>
                      <Badge variant="secondary">{account.type}</Badge>
                    </div>
                    <CurrencyDisplay value={account.currentBalance} />
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          <div className="flex justify-center">
            <Button onClick={resetWizard}>
              <CheckCircle2 />
              {t('sync.finary.done')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function MappingCard({
  account,
  mapping,
  existingAccounts,
  onActionChange,
  onTargetChange,
  onNewAccountField,
}: {
  account: FinaryPreviewResponse['accounts'][number]
  mapping: FinaryAccountMapping
  existingAccounts: Account[]
  onActionChange: (action: FinaryMappingAction) => void
  onTargetChange: (id: number) => void
  onNewAccountField: (field: keyof NewAccountForm, value: string) => void
}) {
  const { t } = useTranslation()

  return (
    <Card size="sm">
      <CardContent className="space-y-3 pt-0">
        {/* Account info row */}
        <div className="flex items-center justify-between">
          <div className="min-w-0">
            <p className="truncate font-medium">{account.finaryName}</p>
            <p className="text-sm text-muted-foreground">
              {account.finaryInstitution} &middot; {account.finaryCategory}
            </p>
          </div>
          <div className="text-right shrink-0 ml-3">
            <CurrencyDisplay value={account.currentBalance} />
            <p className="text-xs text-muted-foreground">
              {account.transactionCount} tx
            </p>
          </div>
        </div>

        {/* Action selector */}
        <div className="flex gap-1.5">
          {(['SKIP', 'MAP_EXISTING', 'CREATE_NEW'] as const).map((action) => (
            <Button
              key={action}
              variant={mapping.action === action ? 'default' : 'outline'}
              size="xs"
              onClick={() => onActionChange(action)}
            >
              {t(`sync.finary.${action === 'SKIP' ? 'skip' : action === 'MAP_EXISTING' ? 'mapExisting' : 'createNew'}`)}
            </Button>
          ))}
        </div>

        {/* MAP_EXISTING: dropdown */}
        {mapping.action === 'MAP_EXISTING' && (
          <select
            className="h-9 w-full rounded-3xl border border-transparent bg-input/50 px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30"
            value={mapping.targetAccountId ?? ''}
            onChange={(e) => {
              const val = e.target.value
              if (val) onTargetChange(Number(val))
            }}
          >
            <option value="" disabled>
              {t('sync.finary.mapExisting')}...
            </option>
            {existingAccounts.map((acc) => (
              <option key={acc.id} value={acc.id}>
                {acc.name} ({acc.type}) &mdash; <CurrencyDisplay value={acc.currentBalance} />
              </option>
            ))}
          </select>
        )}

        {/* CREATE_NEW: inline form */}
        {mapping.action === 'CREATE_NEW' && mapping.newAccount && (
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <Label>{t('accounts.addAccount')}</Label>
              <Input
                value={mapping.newAccount.name}
                onChange={(e) => onNewAccountField('name', e.target.value)}
                placeholder={account.finaryName}
              />
            </div>
            <div className="space-y-1">
              <Label>{t('sync.exchanges.type')}</Label>
              <select
                className="h-9 w-full rounded-3xl border border-transparent bg-input/50 px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30"
                value={mapping.newAccount.type}
                onChange={(e) => onNewAccountField('type', e.target.value)}
              >
                {(['CHECKING', 'SAVINGS', 'LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'OTHER'] as const).map((type) => (
                  <option key={type} value={type}>
                    {t(`accountTypes.${type === 'COMPTE_TITRES' ? 'compteTitres' : type.toLowerCase()}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <Label>{t('sync.wallets.label')}</Label>
              <Input
                value={mapping.newAccount.provider}
                onChange={(e) => onNewAccountField('provider', e.target.value)}
              />
            </div>
            <div className="space-y-1">
              <Label>{t('common.currency')}</Label>
              <Input
                value={mapping.newAccount.currency}
                onChange={(e) => onNewAccountField('currency', e.target.value)}
                maxLength={3}
              />
            </div>
            <div className="space-y-1 sm:col-span-2">
              <Label>Color</Label>
              <div className="flex flex-wrap gap-2">
                {ACCOUNT_COLORS.map((color) => (
                  <button
                    key={color}
                    type="button"
                    className={`size-6 rounded-full border-2 transition-transform hover:scale-110 ${
                      mapping.newAccount?.color === color
                        ? 'border-foreground scale-110'
                        : 'border-transparent'
                    }`}
                    style={{ backgroundColor: color }}
                    onClick={() => onNewAccountField('color', color)}
                  />
                ))}
              </div>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function ResultStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="rounded-xl bg-muted/50 p-4 text-center">
      <p className={`text-2xl font-semibold ${color}`}>{value}</p>
      <p className="mt-1 text-xs text-muted-foreground">{label}</p>
    </div>
  )
}
