import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Ban, ExternalLink, Loader2, Link2, Trash2 } from 'lucide-react'
import { AssetStatusBadge } from '@/components/shared/AssetStatusBadge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAssetCandidates, useApplyAssetMapping, useForgetAssetMapping } from '@/features/assets/hooks'
import { aggregatorLabel, verifyUrl } from '@/features/assets/aggregators'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import type { AssetAggregatorBlock, AssetResponse, AssetStatus } from '@/types/api'

interface AggregatorLinkCardProps {
  symbol: string
  status: AssetStatus | null
  /** The asset's current ref per aggregator (`aggregatorKey → id`), for the collapsed summary. */
  refs: Record<string, string | null>
  /** The holding-detail modal is open — candidates load only once the operator opens the editor. */
  open: boolean
  /** Start with the editor already open (used by the registry table's expandable row). */
  defaultEditing?: boolean
}

/** The stored id an operator hasn't overridden falls back to the suggestion, then to "not linked". */
function effectivePick(block: AssetAggregatorBlock, overrides: Record<string, string>): string {
  return block.aggregatorKey in overrides
    ? overrides[block.aggregatorKey]
    : (block.currentId ?? block.suggestedId ?? '')
}

/**
 * Standing "aggregator links" section of the holding detail / registry — verify or correct which id
 * each aggregator prices a symbol from, any time, outside the import flow.
 *
 * The editor is <b>one picker per aggregator</b> (mirroring the import wizard): each aggregator offers
 * its own candidates, pre-selected with the id it holds today (or its dominant suggestion), plus a
 * "verify" link to eyeball the pick. Mapping the same symbol on several aggregators is what gives it a
 * price fallback when one of them is rate-limited or off. A pasted aggregator link is the escape hatch
 * for a symbol the searches offered nothing for; it's resolved server-side by whichever aggregator
 * recognises it and takes precedence over the pickers. "Mark worthless" and "Forget" are separate
 * actions. Candidates are fetched lazily (only once the editor opens) to stay within the aggregators'
 * free-tier rate limits.
 *
 * Mounted with a per-symbol `key` by the parent, so its local state resets naturally when the operator
 * moves to a different holding — no reset effect needed.
 *
 * Editing is admin-only: the mapping is a global, family-shared registry row, so a change re-values
 * everyone's holdings (the `PUT`/`DELETE` are gated to `ROLE_ADMIN` on the backend). Non-admins see
 * the current links read-only. Demo mode enables the editor so the feature is showcaseable.
 */
export function AggregatorLinkCard({ symbol, status, refs, open, defaultEditing = false }: AggregatorLinkCardProps) {
  const { t } = useTranslation()
  const role = useAuthStore((s) => s.user?.role)
  const demoMode = useAppStore((s) => s.demoMode)
  const canEdit = demoMode || role === 'ADMIN'
  const [editing, setEditing] = useState(defaultEditing && canEdit)
  // Only the aggregators the operator explicitly re-picks live here; everything else derives from the
  // fetched block (current id → suggestion → none), so there's no setState-in-effect to seed picks.
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  const [url, setUrl] = useState('')
  // Reflect a just-applied mapping immediately, regardless of when the parent's portfolio refetch lands.
  const [applied, setApplied] = useState<AssetResponse | null>(null)

  const candidatesQuery = useAssetCandidates(symbol, editing)
  const applyMutation = useApplyAssetMapping()
  const forgetMutation = useForgetAssetMapping()

  const displayStatus = applied ? applied.status : status
  const displayRefs: Record<string, string | null> = applied
    ? { coingecko: applied.coingeckoId, coinmarketcap: applied.coinmarketcapId, yahoo: applied.yahooSymbol }
    : refs
  const linkedEntries = Object.entries(displayRefs).filter(([, id]) => !!id) as [string, string][]

  const blocks = candidatesQuery.data?.aggregators ?? []
  const busy = applyMutation.isPending || forgetMutation.isPending
  const errorMsg = applyMutation.isError || forgetMutation.isError ? t('assets.link.error') : null

  const hasSelection = !!url.trim() || blocks.some((b) => effectivePick(b, overrides))

  // The display name a set of picks suggests: the first picked candidate that carries a name. Sent so
  // correcting a mapping updates the label to the newly-picked asset (the service keeps the old name
  // when none is supplied).
  function suggestedName(ids: Record<string, string>): string | undefined {
    for (const block of blocks) {
      const id = ids[block.aggregatorKey]
      const name = id ? block.candidates.find((c) => c.id === id)?.name : undefined
      if (name) return name
    }
    return undefined
  }

  async function save() {
    try {
      let res: AssetResponse
      const trimmedUrl = url.trim()
      if (trimmedUrl) {
        // Pasted link wins: resolved server-side by whichever aggregator recognises it (setManualMapping).
        res = await applyMutation.mutateAsync({ symbol, data: { action: 'MAP', url: trimmedUrl } })
      } else {
        const ids: Record<string, string> = {}
        for (const block of blocks) {
          const id = effectivePick(block, overrides)
          if (id) ids[block.aggregatorKey] = id
        }
        if (Object.keys(ids).length === 0) return
        res = await applyMutation.mutateAsync({ symbol, data: { action: 'MAP', aggregatorIds: ids, name: suggestedName(ids) } })
      }
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
      // Clears every ref server-side (reverts to PENDING, keeps the row so the holding FK holds).
      const res = await forgetMutation.mutateAsync(symbol)
      setApplied(res)
      setOverrides({})
      setUrl('')
      setEditing(false)
    } catch {
      // Keep the current view; errorMsg shows the failure.
    }
  }

  if (!open) return null
  const hasMapping = linkedEntries.length > 0 || displayStatus === 'WORTHLESS'

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
            ) : linkedEntries.length > 0 ? (
              <div className="space-y-1">
                <span className="text-muted-foreground">{t('assets.link.linkedTo')}</span>
                <ul className="space-y-0.5">
                  {linkedEntries.map(([key, id]) => {
                    const href = verifyUrl(key, id)
                    return (
                      <li key={key} className="flex items-center gap-1.5">
                        <span className="w-24 shrink-0 text-muted-foreground">{aggregatorLabel(key)}</span>
                        {href ? (
                          <a
                            href={href}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 font-mono text-foreground hover:underline"
                          >
                            {id}
                            <ExternalLink className="size-3" />
                          </a>
                        ) : (
                          <span className="font-mono text-foreground">{id}</span>
                        )}
                      </li>
                    )
                  })}
                </ul>
              </div>
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
          <p className="text-xs text-muted-foreground">{t('assets.link.choose')}</p>

          {candidatesQuery.isLoading ? (
            <div className="flex items-center gap-2 py-1 text-xs text-muted-foreground">
              <Loader2 className="size-3.5 animate-spin" />
              {t('assets.link.searching')}
            </div>
          ) : (
            <div className="space-y-2">
              {blocks.map((block) => {
                const pickedId = effectivePick(block, overrides)
                const href = verifyUrl(block.aggregatorKey, pickedId)
                const label = aggregatorLabel(block.aggregatorKey)
                return (
                  <div key={block.aggregatorKey} className="flex items-center gap-2 text-xs">
                    <span className="w-24 shrink-0 text-muted-foreground">{label}</span>
                    <select
                      value={pickedId}
                      onChange={(e) => setOverrides((o) => ({ ...o, [block.aggregatorKey]: e.target.value }))}
                      disabled={block.candidates.length === 0}
                      className="min-w-0 flex-1 rounded-md border bg-background px-2 py-1 text-xs disabled:opacity-50"
                      aria-label={`${symbol} — ${label}`}
                    >
                      <option value="">
                        {block.candidates.length === 0
                          ? t('sync.crypto.optionNoMatch')
                          : t('sync.crypto.optionNoLink')}
                      </option>
                      {block.candidates.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.name ?? c.id}
                          {c.marketCapRank ? ` (#${c.marketCapRank})` : ''}
                        </option>
                      ))}
                    </select>
                    {href ? (
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex shrink-0 items-center text-primary hover:underline"
                        title={t('sync.crypto.verifyOn', { aggregator: label })}
                      >
                        <ExternalLink className="size-3.5" />
                      </a>
                    ) : (
                      <span className="w-3.5 shrink-0" />
                    )}
                  </div>
                )
              })}

              <div className="space-y-1.5 pt-1">
                <label className="text-xs font-medium" htmlFor="asset-link">
                  {t('sync.crypto.linkLabel')}
                </label>
                <Input
                  id="asset-link"
                  type="url"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder={t('sync.crypto.pasteLinkPlaceholder')}
                  className="text-sm"
                />
              </div>
            </div>
          )}

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
              <Button type="button" size="sm" onClick={save} disabled={busy || !hasSelection}>
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
