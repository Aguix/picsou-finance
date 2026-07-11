import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ExternalLink, Loader2, Link2, Trash2 } from 'lucide-react'
import { AssetStatusBadge } from '@/components/shared/AssetStatusBadge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAssetCandidates, useApplyAssetMapping, useForgetAssetMapping } from '@/features/assets/hooks'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import type { AssetResponse, AssetStatus } from '@/types/api'

const COINGECKO_COIN_URL = 'https://www.coingecko.com/en/coins/'

interface AggregatorLinkCardProps {
  symbol: string
  status: AssetStatus | null
  coingeckoId: string | null
  /** The holding-detail modal is open — candidates load only once the operator opens the editor. */
  open: boolean
}

/**
 * Standing "aggregator link" section of the holding detail (D2) — verify or correct which CoinGecko
 * coin a crypto symbol is priced from, any time, outside the import flow. Mirrors the import preview's
 * picker: pick a candidate coin, paste a CoinGecko link when the right coin isn't listed, mark the
 * symbol worthless, or forget the mapping entirely. Candidates are fetched lazily (only once the
 * operator opens the editor) to stay within CoinGecko's free-tier rate limit.
 *
 * Mounted with a per-symbol `key` by the parent, so its local state resets naturally when the
 * operator moves to a different holding — no reset effect needed.
 *
 * Editing is admin-only: the mapping is a global, family-shared registry row, so a change re-values
 * everyone's holdings (the `PUT`/`DELETE` are gated to `ROLE_ADMIN` on the backend). Non-admins see
 * the current link read-only. Demo mode enables the editor so the feature is showcaseable.
 */
export function AggregatorLinkCard({ symbol, status, coingeckoId, open }: AggregatorLinkCardProps) {
  const { t } = useTranslation()
  const role = useAuthStore((s) => s.user?.role)
  const demoMode = useAppStore((s) => s.demoMode)
  const canEdit = demoMode || role === 'ADMIN'
  const [editing, setEditing] = useState(false)
  const [choice, setChoice] = useState<string | null>(null)   // "map:<id>" | "worthless"; null → use default
  const [link, setLink] = useState('')
  // Reflect a just-applied mapping immediately, regardless of when the parent's portfolio refetch lands.
  const [applied, setApplied] = useState<AssetResponse | null>(null)

  const candidatesQuery = useAssetCandidates(symbol, editing)
  const applyMutation = useApplyAssetMapping()
  const forgetMutation = useForgetAssetMapping()

  const displayStatus = applied ? applied.status : status
  const displayCoingeckoId = applied ? applied.coingeckoId : coingeckoId

  const candidates = useMemo(() => candidatesQuery.data?.candidates ?? [], [candidatesQuery.data])

  // Default selection once candidates load: prefer the current mapping, then the market-cap
  // suggestion, then the first candidate; fall back to "worthless" when nothing matches. The
  // operator's explicit pick (`choice`) overrides it.
  const defaultChoice = useMemo(() => {
    const data = candidatesQuery.data
    if (!data) return ''
    const current = displayCoingeckoId && data.candidates.some(c => c.coingeckoId === displayCoingeckoId)
      ? displayCoingeckoId
      : data.suggestedId ?? data.candidates[0]?.coingeckoId ?? null
    return current ? `map:${current}` : 'worthless'
  }, [candidatesQuery.data, displayCoingeckoId])
  const selected = choice ?? defaultChoice

  const busy = applyMutation.isPending || forgetMutation.isPending
  const errorMsg = applyMutation.isError || forgetMutation.isError ? t('assets.link.error') : null

  const selectedCandidate = useMemo(() => {
    if (!selected.startsWith('map:')) return undefined
    const id = selected.slice(4)
    return candidates.find(c => c.coingeckoId === id)
  }, [selected, candidates])

  async function save() {
    const trimmedLink = link.trim()
    try {
      let res: AssetResponse
      if (trimmedLink) {
        res = await applyMutation.mutateAsync({ symbol, data: { action: 'MAP', coingeckoUrl: trimmedLink } })
      } else if (selected === 'worthless') {
        res = await applyMutation.mutateAsync({ symbol, data: { action: 'WORTHLESS' } })
      } else if (selectedCandidate) {
        res = await applyMutation.mutateAsync({
          symbol,
          data: { action: 'MAP', coingeckoId: selectedCandidate.coingeckoId, name: selectedCandidate.name },
        })
      } else {
        return
      }
      setApplied(res)
      setEditing(false)
      setLink('')
    } catch {
      // Error surfaced inline via errorMsg; keep the editor open so the operator can retry.
    }
  }

  async function forget() {
    try {
      await forgetMutation.mutateAsync(symbol)
      setApplied({ symbol, name: null, type: 'CRYPTO', status: null, coingeckoId: null, yahooSymbol: null, lastEurValue: null, priceSyncedAt: null })
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
            <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
              {hasMapping ? t('assets.link.edit') : t('assets.link.resolve')}
            </Button>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {candidatesQuery.isLoading ? (
            <div className="flex items-center gap-2 py-2 text-xs text-muted-foreground">
              <Loader2 className="size-3.5 animate-spin" />
              {t('assets.link.searching')}
            </div>
          ) : (
            <>
              <div className="space-y-1.5">
                <label className="text-xs font-medium" htmlFor="asset-candidate">
                  {t('assets.link.pickCoin')}
                </label>
                <select
                  id="asset-candidate"
                  value={selected}
                  onChange={(e) => setChoice(e.target.value)}
                  disabled={!!link.trim()}
                  className="w-full rounded-md border bg-background px-2 py-1.5 text-sm disabled:opacity-50"
                >
                  {candidates.length === 0 && !link.trim() && (
                    <option value="" disabled>{t('assets.link.noCandidates')}</option>
                  )}
                  {candidates.map((c) => (
                    <option key={c.coingeckoId} value={`map:${c.coingeckoId}`}>
                      {c.name}{c.marketCapRank ? ` (#${c.marketCapRank})` : ''}
                    </option>
                  ))}
                  <option value="worthless">{t('assets.link.optionWorthless')}</option>
                </select>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-medium" htmlFor="asset-link">
                  {t('assets.link.pasteLink')}
                </label>
                <Input
                  id="asset-link"
                  value={link}
                  onChange={(e) => setLink(e.target.value)}
                  placeholder={`${COINGECKO_COIN_URL}…`}
                  className="text-sm"
                />
                <p className="text-[11px] text-muted-foreground">{t('assets.link.pasteHint')}</p>
              </div>
            </>
          )}

          {errorMsg && <p className="text-xs text-red-500">{errorMsg}</p>}

          <div className="flex items-center justify-between gap-2">
            {hasMapping ? (
              <Button
                variant="ghost"
                size="sm"
                className="text-muted-foreground hover:text-destructive"
                onClick={forget}
                disabled={busy}
              >
                <Trash2 className="mr-1.5 size-3.5" />
                {t('assets.link.forget')}
              </Button>
            ) : <span />}
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" onClick={() => { setEditing(false); setLink(''); setChoice(null) }} disabled={busy}>
                {t('common.cancel')}
              </Button>
              <Button size="sm" onClick={save} disabled={busy || candidatesQuery.isLoading}>
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
