import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Upload, FileText, ArrowRight, CheckCircle2, Gift, ScanSearch, HelpCircle } from 'lucide-react'
import {
  usePreviewCryptoCsv,
  useImportCrypto,
  useCryptoSources,
  useMarkCoinWorthless,
  useResolveCoin,
} from '@/features/crypto/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { formatLocalDate } from '@/lib/utils'
import { REWARD_KIND_LABELS } from '@/features/crypto/labels'
import type { CryptoPreviewResponse, RewardKind } from '@/types/api'

/**
 * Multi-exchange crypto CSV import: the user drops any supported export (Crypto.com App/Exchange,
 * Kraken, Binance, Bybit, Bitstack, Ledger Live, or the documented generic format) and the
 * backend auto-detects the source before the preview → import flow.
 */
export function CryptoImportTab() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const { data: sources } = useCryptoSources()
  const previewMutation = usePreviewCryptoCsv()
  const importMutation = useImportCrypto()

  const [preview, setPreview] = useState<CryptoPreviewResponse | null>(null)
  const [target, setTarget] = useState<'CREATE_NEW' | number>('CREATE_NEW')
  const [accountName, setAccountName] = useState('')

  function handleFile(file: File) {
    setPreview(null)
    previewMutation.mutate(file, {
      onSuccess: (data) => {
        setPreview(data)
        setAccountName(data.sourceLabel)
      },
    })
  }

  function handleImport() {
    if (!preview) return
    importMutation.mutate({
      fileToken: preview.fileToken,
      action: target === 'CREATE_NEW' ? 'CREATE_NEW' : 'MAP_EXISTING',
      targetAccountId: target === 'CREATE_NEW' ? undefined : target,
      accountName: target === 'CREATE_NEW' ? accountName : undefined,
    })
  }

  function reset() {
    setPreview(null)
    setTarget('CREATE_NEW')
    setAccountName('')
    importMutation.reset()
    previewMutation.reset()
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  // ─── Success ──────────────────────────────────────────────────────────────
  if (importMutation.isSuccess && importMutation.data) {
    const r = importMutation.data
    return (
      <Card>
        <CardContent className="flex flex-col items-center gap-4 py-10 text-center">
          <CheckCircle2 className="size-10 text-emerald-500" />
          <div>
            <p className="font-semibold">{t('sync.crypto.done', 'Import terminé')}</p>
            <p className="text-sm text-muted-foreground">
              {t('sync.crypto.doneDetail', {
                defaultValue: '{{tx}} transactions · {{holdings}} cryptos',
                tx: r.transactionsImported,
                holdings: r.holdingsCount,
              })}
            </p>
          </div>
          <div className="flex gap-2">
            <Button onClick={() => navigate(`/crypto?account=${r.accountId}`)}>
              {t('sync.crypto.viewStats', 'Voir les statistiques')}
              <ArrowRight />
            </Button>
            <Button variant="outline" onClick={reset}>
              {t('sync.crypto.importAnother', 'Importer un autre fichier')}
            </Button>
          </div>
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        {t(
          'sync.crypto.intro',
          "Déposez l'export CSV de votre exchange ou wallet — le format est détecté automatiquement.",
        )}
      </p>

      {/* Supported sources */}
      {!preview && sources && sources.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">
            {t('sync.crypto.supported', 'Formats supportés')}:
          </span>
          {sources.map((s) => (
            <Badge key={s.id} variant="outline">{s.label}</Badge>
          ))}
        </div>
      )}

      {/* Upload */}
      {!preview && (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-10">
            <Upload className="size-8 text-muted-foreground" />
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) handleFile(f)
              }}
            />
            <Button onClick={() => fileInputRef.current?.click()} disabled={previewMutation.isPending}>
              <FileText />
              {previewMutation.isPending
                ? t('common.loading', 'Chargement…')
                : t('sync.crypto.choose', 'Choisir un fichier CSV')}
            </Button>
            {previewMutation.isError && (
              <p className="text-sm text-red-500">{extractErrorMessage(previewMutation.error)}</p>
            )}
          </CardContent>
        </Card>
      )}

      {/* Preview + mapping */}
      {preview && (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                {t('sync.crypto.detected', 'Détecté dans le fichier')}
                <Badge variant="secondary" className="gap-1">
                  <ScanSearch className="size-3.5" />
                  {preview.sourceLabel}
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Stat label={t('sync.crypto.transactions', 'Transactions')} value={String(preview.transactionCount)} />
                <Stat label={t('sync.crypto.buys', 'Achats')} value={String(preview.buyCount)} />
                <Stat label={t('sync.crypto.rewards', 'Récompenses')} value={String(preview.rewardCount)} />
                <Stat label={t('sync.crypto.coins', 'Cryptos')} value={String(preview.currencies.length)} />
              </div>

              {(preview.firstDate || preview.lastDate) && (
                <p className="text-xs text-muted-foreground">
                  {t('sync.crypto.period', 'Période')}:{' '}
                  {preview.firstDate ? formatLocalDate(preview.firstDate) : '?'} →{' '}
                  {preview.lastDate ? formatLocalDate(preview.lastDate) : '?'}
                </p>
              )}

              <div className="flex flex-wrap gap-1.5">
                {preview.currencies.slice(0, 24).map((c) => (
                  <Badge key={c} variant="secondary">{c}</Badge>
                ))}
                {preview.currencies.length > 24 && (
                  <Badge variant="outline">+{preview.currencies.length - 24}</Badge>
                )}
              </div>

              <div className="flex flex-col gap-2 rounded-lg border p-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-center gap-2 text-sm">
                  <Gift className="size-4 text-amber-500" />
                  <span>{t('sync.crypto.totalRewards', 'Total récompenses')}</span>
                </div>
                <CurrencyDisplay value={preview.totalRewards} className="font-semibold text-amber-600" />
              </div>

              {Object.keys(preview.rewardsByKind).length > 0 && (
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                  {(Object.entries(preview.rewardsByKind) as [RewardKind, number][])
                    .sort((a, b) => b[1] - a[1])
                    .map(([kind, value]) => (
                      <div key={kind} className="flex items-center justify-between rounded-md bg-muted/40 px-3 py-2 text-sm">
                        <span className="text-muted-foreground">{REWARD_KIND_LABELS[kind] ?? kind}</span>
                        <CurrencyDisplay value={value} className="font-medium" />
                      </div>
                    ))}
                </div>
              )}

              {preview.unvaluedCount > 0 && (
                <p className="text-xs text-muted-foreground">
                  {t('sync.crypto.unvalued', {
                    defaultValue:
                      "{{count}} ligne(s) sans valeur en euro dans le CSV — elles seront valorisées à l'import via l'historique des prix.",
                    count: preview.unvaluedCount,
                  })}
                </p>
              )}

              {preview.unknownCount > 0 && (
                <p className="text-xs text-muted-foreground">
                  {t('sync.crypto.unknown', {
                    defaultValue:
                      '{{count}} ligne(s) de type inconnu seront importées sans impacter les positions.',
                    count: preview.unknownCount,
                  })}
                </p>
              )}
            </CardContent>
          </Card>

          {/* Coins CoinGecko couldn't auto-resolve — ask for their CoinGecko link */}
          {preview.unresolvedTickers.length > 0 && (
            <UnresolvedCoins tickers={preview.unresolvedTickers} />
          )}

          {/* Target account */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('sync.crypto.target', 'Compte de destination')}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap gap-2">
                <Button
                  variant={target === 'CREATE_NEW' ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => setTarget('CREATE_NEW')}
                >
                  {t('sync.crypto.createNew', 'Nouveau compte')}
                </Button>
                {preview.existingAccounts.map((a) => (
                  <Button
                    key={a.id}
                    variant={target === a.id ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setTarget(a.id)}
                  >
                    {a.name}
                  </Button>
                ))}
              </div>

              <p className="text-xs text-muted-foreground">
                {t(
                  'sync.crypto.oneAccountPerSource',
                  'Conseil : un compte par exchange/wallet — un ré-import remplace les transactions importées du compte ciblé (les entrées manuelles sont conservées).',
                )}
              </p>

              {target === 'CREATE_NEW' && (
                <div className="space-y-2">
                  <Label htmlFor="crypto-account-name">{t('sync.crypto.accountName', 'Nom du compte')}</Label>
                  <Input
                    id="crypto-account-name"
                    value={accountName}
                    onChange={(e) => setAccountName(e.target.value)}
                  />
                </div>
              )}

              {importMutation.isError && (
                <p className="text-sm text-red-500">{extractErrorMessage(importMutation.error)}</p>
              )}

              <div className="flex gap-2 pt-1">
                <Button onClick={handleImport} disabled={importMutation.isPending}>
                  {importMutation.isPending
                    ? t('common.loading', 'Chargement…')
                    : t('sync.crypto.import', 'Importer')}
                </Button>
                <Button variant="outline" onClick={reset}>
                  {t('common.cancel', 'Annuler')}
                </Button>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-muted/40 px-3 py-2">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-lg font-semibold">{value}</p>
    </div>
  )
}

/**
 * Coins the backend couldn't map to a CoinGecko id (ambiguous symbol or unknown). The operator
 * pastes each coin's CoinGecko page link so it can be priced. Purely optional — an unresolved coin
 * just imports unpriced — so a resolved coin drops off the list and the import stays available.
 */
function UnresolvedCoins({ tickers }: { tickers: string[] }) {
  const { t } = useTranslation()
  const [resolved, setResolved] = useState<Set<string>>(new Set())
  const pending = tickers.filter((tk) => !resolved.has(tk))
  if (pending.length === 0) return null

  return (
    <Card className="border-amber-500/40">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <HelpCircle className="size-4 text-amber-500" />
          {t('sync.crypto.unresolved.title', 'Cryptos à identifier')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          {t(
            'sync.crypto.unresolved.hint',
            "Ces symboles n'ont pas pu être associés automatiquement à une crypto (symbole ambigu ou inconnu). Collez le lien de leur page CoinGecko pour les valoriser — sinon elles seront importées sans prix.",
          )}
        </p>
        {pending.map((ticker) => (
          <CoinResolveRow
            key={ticker}
            ticker={ticker}
            onResolved={(tk) => setResolved((prev) => new Set(prev).add(tk))}
          />
        ))}
      </CardContent>
    </Card>
  )
}

function CoinResolveRow({ ticker, onResolved }: { ticker: string; onResolved: (ticker: string) => void }) {
  const { t } = useTranslation()
  const [url, setUrl] = useState('')
  const resolveMutation = useResolveCoin()
  const worthlessMutation = useMarkCoinWorthless()

  function submit() {
    const link = url.trim()
    if (!link) return
    resolveMutation.mutate({ ticker, coingeckoUrl: link }, { onSuccess: () => onResolved(ticker) })
  }

  const busy = resolveMutation.isPending || worthlessMutation.isPending

  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="secondary" className="min-w-14 justify-center">{ticker}</Badge>
        <Input
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://www.coingecko.com/en/coins/…"
          className="min-w-0 flex-1"
          onKeyDown={(e) => {
            if (e.key === 'Enter') submit()
          }}
        />
        <Button
          size="sm"
          variant="outline"
          onClick={submit}
          disabled={busy || !url.trim()}
        >
          {resolveMutation.isPending
            ? t('common.loading', 'Chargement…')
            : t('sync.crypto.unresolved.link', 'Associer')}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          className="text-muted-foreground"
          disabled={busy}
          onClick={() => worthlessMutation.mutate(ticker, { onSuccess: () => onResolved(ticker) })}
          title={t('sync.crypto.unresolved.worthlessHint', 'Crypto délistée, sans prix disponible')}
        >
          {worthlessMutation.isPending
            ? t('common.loading', 'Chargement…')
            : t('sync.crypto.unresolved.worthless', 'Sans valeur')}
        </Button>
      </div>
      {resolveMutation.isError && (
        <p className="text-xs text-red-500">{extractErrorMessage(resolveMutation.error)}</p>
      )}
      {worthlessMutation.isError && (
        <p className="text-xs text-red-500">{extractErrorMessage(worthlessMutation.error)}</p>
      )}
    </div>
  )
}
