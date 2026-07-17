import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, ExternalLink, Loader2, Pencil } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { AssetStatusBadge } from '@/components/shared/AssetStatusBadge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PriceFreshnessDot } from '@/components/shared/PriceFreshnessDot'
import { AggregatorLinkCard } from '@/components/shared/AggregatorLinkCard'
import { useAssets, useApplyAssetMapping } from '@/features/assets/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import type { AssetResponse, AssetStatus } from '@/types/api'

const COINGECKO_COIN_URL = 'https://www.coingecko.com/en/coins/'

// Surface the rows that need attention first: PENDING (unpriced) then AUTO (a guess to ratify),
// then the settled ones.
const STATUS_ORDER: Record<string, number> = { PENDING: 0, AUTO: 1, USER: 2, WORTHLESS: 3 }

// The asset's non-null refs as `aggregatorKey → id` — the shape applyMappings/confirm want. One line
// per aggregator column, kept in sync with AssetResponse; a new aggregator ref adds an entry here.
function refsOf(asset: AssetResponse): Record<string, string> {
  const all: Record<string, string | null> = {
    coingecko: asset.coingeckoId,
    coinmarketcap: asset.coinmarketcapId,
    yahoo: asset.yahooSymbol,
  }
  return Object.fromEntries(Object.entries(all).filter(([, id]) => !!id)) as Record<string, string>
}

interface AssetRegistryModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Standing management table for the whole `financial_asset` registry: one row per asset, one column
 * per aggregator (CoinGecko / CoinMarketCap / Yahoo), plus its resolution status. Admins can confirm
 * an automatic guess in one click or open the per-symbol editor (one picker per aggregator) to
 * correct/mark-worthless/forget. Opened from `/accounts` (next to "Add account") and from the admin
 * price-aggregators section.
 *
 * Confirming/editing is gated to crypto rows for now — a product decision, not an engine limit: the
 * editor resolves across every aggregator, but surfacing stock/ETF (Yahoo/ISIN) resolution is a later
 * pass. Confirm ratifies whatever refs the AUTO row already carries, across every aggregator.
 */
export function AssetRegistryModal({ open, onOpenChange }: AssetRegistryModalProps) {
  const { t } = useTranslation()
  const role = useAuthStore((s) => s.user?.role)
  const demoMode = useAppStore((s) => s.demoMode)
  const canEdit = demoMode || role === 'ADMIN'

  const { data: assets, isLoading } = useAssets(open)
  const applyMutation = useApplyAssetMapping()
  const [expanded, setExpanded] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)

  const sorted = useMemo(() => {
    if (!assets) return []
    return [...assets].sort((a, b) => {
      const sa = STATUS_ORDER[a.status ?? ''] ?? 9
      const sb = STATUS_ORDER[b.status ?? ''] ?? 9
      return sa - sb || a.symbol.localeCompare(b.symbol)
    })
  }, [assets])

  // symbol, name, type, one per aggregator (CoinGecko/CoinMarketCap/Yahoo), value, status — plus
  // actions for an admin. Keep in sync with the header row below.
  const colCount = canEdit ? 9 : 8

  // Ratify an AUTO row: re-pin whatever refs it already carries (across every aggregator) as USER,
  // unchanged — so confirming preserves a multi-aggregator mapping, not just CoinGecko.
  function confirm(asset: AssetResponse) {
    const ids = refsOf(asset)
    if (Object.keys(ids).length === 0) return
    setConfirming(asset.symbol)
    applyMutation.mutate(
      { symbol: asset.symbol, data: { action: 'MAP', aggregatorIds: ids, name: asset.name ?? undefined } },
      { onSettled: () => setConfirming(null) },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-6xl lg:max-w-[80rem] max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t('assets.registry.title')}</DialogTitle>
          <p className="text-sm text-muted-foreground">{t('assets.registry.subtitle')}</p>
        </DialogHeader>

        {isLoading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          </div>
        ) : sorted.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">{t('assets.registry.empty')}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('assets.registry.colSymbol')}</TableHead>
                <TableHead>{t('assets.registry.colName')}</TableHead>
                <TableHead>{t('assets.registry.colType')}</TableHead>
                <TableHead>CoinGecko</TableHead>
                <TableHead>CoinMarketCap</TableHead>
                <TableHead>Yahoo</TableHead>
                <TableHead className="text-right">{t('assets.registry.colValue')}</TableHead>
                <TableHead>{t('assets.registry.colStatus')}</TableHead>
                {canEdit && <TableHead className="text-right">{t('assets.registry.colActions')}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {sorted.map((asset) => {
                const isCrypto = asset.type === 'CRYPTO'
                const isExpanded = expanded === asset.symbol
                return (
                  <FragmentRow key={asset.symbol}>
                    <TableRow>
                      <TableCell className="font-mono font-medium">{asset.symbol}</TableCell>
                      <TableCell className="max-w-[14rem] truncate">{asset.name ?? '—'}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">{asset.type ?? '—'}</TableCell>
                      <TableCell>
                        {asset.coingeckoId ? (
                          <a
                            href={`${COINGECKO_COIN_URL}${asset.coingeckoId}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 font-mono text-xs text-primary hover:underline"
                          >
                            {asset.coingeckoId}
                            <ExternalLink className="size-3" />
                          </a>
                        ) : (
                          <span className="text-xs text-muted-foreground">{'—'}</span>
                        )}
                      </TableCell>
                      {/* Displayed like the Yahoo column; both are editable from the per-aggregator
                          editor below (expand a crypto row). A second ref here is what gives the asset
                          a price fallback when CoinGecko is rate-limited. */}
                      <TableCell className="font-mono text-xs">
                        {asset.coinmarketcapId ?? <span className="text-muted-foreground">{'—'}</span>}
                      </TableCell>
                      <TableCell className="font-mono text-xs">
                        {asset.yahooSymbol ?? <span className="text-muted-foreground">{'—'}</span>}
                      </TableCell>
                      <TableCell className="text-right">
                        {/* Only trust the value as a validation signal when it comes from a real
                            pricing link: a crypto with no CoinGecko id would show a spurious Yahoo
                            fallback quote (or a stale leftover), which is exactly what the column is
                            meant to help catch — so hide it. Non-crypto (Yahoo-priced) rows keep it. */}
                        {asset.lastEurValue != null && (!isCrypto || !!asset.coingeckoId) ? (
                          <div className="inline-flex items-center gap-1.5">
                            <PriceFreshnessDot priceUpdatedAt={asset.priceSyncedAt} />
                            <CurrencyDisplay value={asset.lastEurValue} className="text-sm tabular-nums" />
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">{'—'}</span>
                        )}
                      </TableCell>
                      <TableCell><AssetStatusBadge status={asset.status as AssetStatus | null} /></TableCell>
                      {canEdit && (
                        <TableCell className="text-right">
                          {isCrypto ? (
                            <div className="inline-flex items-center justify-end gap-1">
                              {asset.status === 'AUTO' && Object.keys(refsOf(asset)).length > 0 && (
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="icon"
                                  className="size-8 text-emerald-600 hover:text-emerald-700 dark:text-emerald-400"
                                  disabled={applyMutation.isPending}
                                  onClick={() => confirm(asset)}
                                  title={t('assets.registry.confirm')}
                                  aria-label={t('assets.registry.confirm')}
                                >
                                  {confirming === asset.symbol
                                    ? <Loader2 className="size-4 animate-spin" />
                                    : <CheckCircle2 className="size-4" />}
                                </Button>
                              )}
                              <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                className={cn('size-8', isExpanded && 'bg-muted text-foreground')}
                                onClick={() => setExpanded(isExpanded ? null : asset.symbol)}
                                title={t('assets.registry.edit')}
                                aria-label={t('assets.registry.edit')}
                              >
                                <Pencil className="size-4" />
                              </Button>
                            </div>
                          ) : (
                            <span className="text-xs text-muted-foreground">{'—'}</span>
                          )}
                        </TableCell>
                      )}
                    </TableRow>
                    {isExpanded && (
                      <TableRow>
                        <TableCell colSpan={colCount} className="bg-muted/30">
                          <AggregatorLinkCard
                            key={`reg-${asset.symbol}`}
                            symbol={asset.symbol}
                            status={asset.status as AssetStatus | null}
                            refs={{ coingecko: asset.coingeckoId, coinmarketcap: asset.coinmarketcapId, yahoo: asset.yahooSymbol }}
                            open
                            defaultEditing
                          />
                        </TableCell>
                      </TableRow>
                    )}
                  </FragmentRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </DialogContent>
    </Dialog>
  )
}

// A <tbody> can't hold a fragment child that isn't a row, but two sibling <TableRow>s need a single
// keyed parent — this thin wrapper returns them as a fragment so the key lives on the map element.
function FragmentRow({ children }: { children: React.ReactNode }) {
  return <>{children}</>
}
