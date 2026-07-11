import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Ban, ExternalLink, Loader2, Link2, Trash2 } from 'lucide-react'
import { AssetStatusBadge } from '@/components/shared/AssetStatusBadge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { useAssetCandidates, useApplyAssetMapping, useForgetAssetMapping } from '@/features/assets/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import type { AssetResponse, AssetStatus } from '@/types/api'

const COINGECKO_COIN_URL = 'https://www.coingecko.com/en/coins/'

/** Grab the coin-id slug from a CoinGecko coin URL (mirrors the backend regex), else null. */
function extractCoinId(url: string): string | null {
  const m = /\/coins\/([^/?#]+)/.exec(url.trim())
  return m ? m[1].trim().toLowerCase() : null
}

interface AggregatorLinkCardProps {
  symbol: string
  status: AssetStatus | null
  coingeckoId: string | null
  /** The holding-detail modal is open — candidates load only once the operator opens the editor. */
  open: boolean
  /** Start with the editor already open (used by the registry table's expandable row). */
  defaultEditing?: boolean
}

/**
 * Standing "aggregator link" section of the holding detail (D2) — verify or correct which CoinGecko
 * coin a crypto symbol is priced from, any time, outside the import flow.
 *
 * The editor is <b>link-first</b>: one "CoinGecko link" field is the single source of truth for the
 * coin, pre-filled with the current mapping's URL so a settled coin is visible and editable. The
 * CoinGecko search matches are offered as one-click <em>suggestions</em> that fill that field (so the
 * operator rarely types a URL by hand); picking a suggestion pins it without an extra CoinGecko
 * round-trip, while a hand-pasted link is validated server-side. "Mark worthless" and "Forget" are
 * separate actions, not entries in the coin picker. Candidates are fetched lazily (only once the
 * editor opens) to stay within CoinGecko's free-tier rate limit.
 *
 * Mounted with a per-symbol `key` by the parent, so its local state resets naturally when the
 * operator moves to a different holding — no reset effect needed.
 *
 * Editing is admin-only: the mapping is a global, family-shared registry row, so a change re-values
 * everyone's holdings (the `PUT`/`DELETE` are gated to `ROLE_ADMIN` on the backend). Non-admins see
 * the current link read-only. Demo mode enables the editor so the feature is showcaseable.
 */
export function AggregatorLinkCard({ symbol, status, coingeckoId, open, defaultEditing = false }: AggregatorLinkCardProps) {
  const { t } = useTranslation()
  const role = useAuthStore((s) => s.user?.role)
  const demoMode = useAppStore((s) => s.demoMode)
  const canEdit = demoMode || role === 'ADMIN'
  const [editing, setEditing] = useState(defaultEditing && canEdit)
  // Pre-fill the link with the current coin's URL. Safe as a lazy init: the component is remounted
  // per symbol (`key`), so this runs once with the right coin for the row.
  const [link, setLink] = useState(() => (coingeckoId ? `${COINGECKO_COIN_URL}${coingeckoId}` : ''))
  // Reflect a just-applied mapping immediately, regardless of when the parent's portfolio refetch lands.
  const [applied, setApplied] = useState<AssetResponse | null>(null)

  const candidatesQuery = useAssetCandidates(symbol, editing)
  const applyMutation = useApplyAssetMapping()
  const forgetMutation = useForgetAssetMapping()

  const displayStatus = applied ? applied.status : status
  const displayCoingeckoId = applied ? applied.coingeckoId : coingeckoId

  const candidates = useMemo(() => candidatesQuery.data?.candidates ?? [], [candidatesQuery.data])
  const linkCoinId = useMemo(() => extractCoinId(link), [link])

  const busy = applyMutation.isPending || forgetMutation.isPending
  const errorMsg = applyMutation.isError || forgetMutation.isError ? t('assets.link.error') : null

  async function save() {
    const trimmed = link.trim()
    if (!trimmed) return
    // A link that matches a known candidate is pinned by id (no extra CoinGecko call); anything else
    // (a hand-pasted URL) goes through setManualMapping server-side, which validates it.
    const match = linkCoinId ? candidates.find((c) => c.coingeckoId === linkCoinId) : undefined
    try {
      const res = match
        ? await applyMutation.mutateAsync({ symbol, data: { action: 'MAP', coingeckoId: match.coingeckoId, name: match.name } })
        : await applyMutation.mutateAsync({ symbol, data: { action: 'MAP', coingeckoUrl: trimmed } })
      setApplied(res)
      setEditing(false)
    } catch {
      // Error surfaced inline via errorMsg; keep the editor open so the operator can retry.
    }
  }

  async function markWorthless() {
    try {
      const res = await applyMutation.mutateAsync({ symbol, data: { action: 'WORTHLESS' } })
      setApplied(res)
      setEditing(false)
    } catch {
      // Keep the editor open; errorMsg shows the failure.
    }
  }

  async function forget() {
    try {
      // Clears the link server-side (reverts to PENDING, keeps the row so the holding FK holds).
      const res = await forgetMutation.mutateAsync(symbol)
      setApplied(res)
      setLink('')
      setEditing(false)
    } catch {
      // Keep the current view; errorMsg shows the failure.
    }
  }

  if (!open) return null
  const hasMapping = !!displayCoingeckoId || displayStatus === 'WORTHLESS'

  return (
    <div className="space-y-3 rounded-xl border p-4">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Link2 className="size-4 text-muted-foreground" />
          <p className="text-sm font-medium">{t('assets.link.title')}</p>
        </div>
        <AssetStatusBadge status={displayStatus} />
      </div>

      {!editing ? (
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0 text-xs text-muted-foreground">
            {displayStatus === 'WORTHLESS' ? (
              <span>{t('assets.link.worthlessNote')}</span>
            ) : displayCoingeckoId ? (
              <span className="inline-flex items-center gap-1.5">
                <span className="text-muted-foreground">{t('assets.link.linkedTo')}</span>
                <a
                  href={`${COINGECKO_COIN_URL}${displayCoingeckoId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-mono text-foreground hover:underline"
                >
                  {displayCoingeckoId}
                  <ExternalLink className="size-3" />
                </a>
              </span>
            ) : (
              <span>{t('assets.link.unresolvedNote')}</span>
            )}
          </div>
          {canEdit && (
            <Button type="button" variant="outline" size="sm" onClick={() => setEditing(true)}>
              {hasMapping ? t('assets.link.edit') : t('assets.link.resolve')}
            </Button>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="space-y-1.5">
            <label className="text-xs font-medium" htmlFor="asset-link">
              {t('assets.link.coinLink')}
            </label>
            <Input
              id="asset-link"
              value={link}
              onChange={(e) => setLink(e.target.value)}
              placeholder={`${COINGECKO_COIN_URL}…`}
              className="text-sm"
            />
            <p className="text-[11px] text-muted-foreground">{t('assets.link.linkHint')}</p>
          </div>

          {candidatesQuery.isLoading ? (
            <div className="flex items-center gap-2 py-1 text-xs text-muted-foreground">
              <Loader2 className="size-3.5 animate-spin" />
              {t('assets.link.searching')}
            </div>
          ) : candidates.length > 0 ? (
            <div className="space-y-1.5">
              <p className="text-xs font-medium">{t('assets.link.suggestions')}</p>
              <div className="flex flex-wrap gap-1.5">
                {candidates.map((c) => {
                  const active = linkCoinId === c.coingeckoId
                  return (
                    <button
                      type="button"
                      key={c.coingeckoId}
                      onClick={() => setLink(`${COINGECKO_COIN_URL}${c.coingeckoId}`)}
                      className={cn(
                        'rounded-full border px-2.5 py-1 text-xs transition-colors',
                        active
                          ? 'border-primary bg-primary/10 text-primary'
                          : 'border-border text-foreground hover:bg-muted',
                      )}
                    >
                      {c.name}{c.marketCapRank ? ` · #${c.marketCapRank}` : ''}
                    </button>
                  )
                })}
              </div>
            </div>
          ) : null}

          {errorMsg && <p className="text-xs text-red-500">{errorMsg}</p>}

          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="text-muted-foreground hover:text-foreground"
                onClick={markWorthless}
                disabled={busy || displayStatus === 'WORTHLESS'}
              >
                <Ban className="mr-1.5 size-3.5" />
                {t('assets.link.optionWorthless')}
              </Button>
              {hasMapping && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-muted-foreground hover:text-destructive"
                  onClick={forget}
                  disabled={busy}
                >
                  <Trash2 className="mr-1.5 size-3.5" />
                  {t('assets.link.forget')}
                </Button>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button type="button" size="sm" onClick={save} disabled={busy || !link.trim()}>
                {busy && <Loader2 className="mr-1.5 size-3.5 animate-spin" />}
                {t('common.save')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
