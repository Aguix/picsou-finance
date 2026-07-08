import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { CircleSlash, ExternalLink, Loader2, Pencil, Trash2, X } from 'lucide-react'
import {
  useCoinMappings,
  useDeleteCoinMapping,
  useMarkCoinWorthless,
  useResolveCoin,
} from '@/features/crypto/hooks'
import { extractErrorMessage } from '@/lib/errors'
import type { CoinMappingResponse } from '@/types/api'

/**
 * Management UI for the ticker → CoinGecko mappings created at import time. A mapping can be
 * wrong (an ambiguous symbol auto-resolved to the wrong coin, or a bad link was pasted) — the
 * operator corrects it by pasting the right CoinGecko page link, or forgets it entirely. The
 * backend then purges the ticker's price history fetched under the wrong coin and refetches it.
 */
export function CoinMappingsDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const { data: mappings, isLoading } = useCoinMappings(open)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t('crypto.mappings.title', 'Correspondances CoinGecko')}</DialogTitle>
          <DialogDescription>
            {t(
              'crypto.mappings.desc',
              "Chaque symbole importé est associé à une crypto CoinGecko pour être valorisé. En cas d'erreur, collez le lien de la bonne page CoinGecko — l'historique de prix du symbole sera re-téléchargé.",
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[60vh] space-y-1.5 overflow-y-auto pr-1">
          {isLoading && (
            <div className="flex justify-center py-6">
              <Loader2 className="size-5 animate-spin text-muted-foreground" />
            </div>
          )}
          {!isLoading && (!mappings || mappings.length === 0) && (
            <p className="py-6 text-center text-sm text-muted-foreground">
              {t(
                'crypto.mappings.empty',
                'Aucune correspondance pour le moment — elles sont créées lors des imports crypto.',
              )}
            </p>
          )}
          {mappings?.map((m) => <MappingRow key={m.ticker} mapping={m} />)}
        </div>
      </DialogContent>
    </Dialog>
  )
}

function MappingRow({ mapping }: { mapping: CoinMappingResponse }) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState(false)
  const [url, setUrl] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const resolveMutation = useResolveCoin()
  const deleteMutation = useDeleteCoinMapping()
  const worthlessMutation = useMarkCoinWorthless()

  const worthless = mapping.resolvedVia === 'WORTHLESS'

  function submitCorrection() {
    const link = url.trim()
    if (!link) return
    resolveMutation.mutate(
      { ticker: mapping.ticker, coingeckoUrl: link },
      {
        onSuccess: () => {
          setEditing(false)
          setUrl('')
        },
      },
    )
  }

  const busy = resolveMutation.isPending || deleteMutation.isPending || worthlessMutation.isPending

  return (
    <div className="rounded-lg border p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="secondary" className="min-w-14 justify-center">{mapping.ticker}</Badge>
        {worthless ? (
          <span className="inline-flex items-center gap-1 text-sm text-muted-foreground">
            <CircleSlash className="size-3.5" />
            {t('crypto.mappings.worthlessLabel', 'Sans valeur (délistée)')}
          </span>
        ) : (
          <a
            href={`https://www.coingecko.com/en/coins/${mapping.coingeckoId}`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-w-0 items-center gap-1 text-sm hover:underline"
          >
            <span className="truncate">
              {mapping.coinName ?? mapping.coingeckoId}
              <span className="ml-1 text-xs text-muted-foreground">({mapping.coingeckoId})</span>
            </span>
            <ExternalLink className="size-3 shrink-0 text-muted-foreground" />
          </a>
        )}
        <div className="ml-auto flex items-center gap-1">
          <Badge variant="outline" className="text-xs">
            {worthless
              ? t('crypto.mappings.viaWorthless', 'Sans valeur')
              : mapping.resolvedVia === 'USER'
                ? t('crypto.mappings.viaUser', 'Manuel')
                : t('crypto.mappings.viaAuto', 'Auto')}
          </Badge>
          <Button
            variant="ghost"
            size="icon"
            disabled={busy}
            onClick={() => {
              setEditing((v) => !v)
              setConfirmDelete(false)
            }}
            title={worthless ? t('crypto.mappings.restore', 'Rétablir le prix') : t('crypto.mappings.edit', 'Corriger')}
          >
            {editing ? <X className="size-3.5" /> : <Pencil className="size-3.5" />}
          </Button>
          {confirmDelete ? (
            <Button
              variant="destructive"
              size="sm"
              className="h-7"
              disabled={busy}
              onClick={() => deleteMutation.mutate(mapping.ticker)}
            >
              {deleteMutation.isPending
                ? t('common.loading', 'Chargement…')
                : t('crypto.mappings.confirmDelete', 'Confirmer ?')}
            </Button>
          ) : (
            <Button
              variant="ghost"
              size="icon"
              className="text-destructive hover:text-destructive"
              disabled={busy}
              onClick={() => setConfirmDelete(true)}
              title={t('crypto.mappings.delete', 'Oublier')}
            >
              <Trash2 className="size-3.5" />
            </Button>
          )}
        </div>
      </div>

      {editing && (
        <div className="mt-2 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://www.coingecko.com/en/coins/…"
              className="min-w-0 flex-1"
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter') submitCorrection()
              }}
            />
            <Button
              size="sm"
              variant="outline"
              onClick={submitCorrection}
              disabled={resolveMutation.isPending || !url.trim()}
            >
              {resolveMutation.isPending
                ? t('common.loading', 'Chargement…')
                : worthless
                  ? t('crypto.mappings.restore', 'Rétablir le prix')
                  : t('crypto.mappings.apply', 'Corriger')}
            </Button>
          </div>
          {!worthless && (
            <Button
              variant="ghost"
              size="sm"
              className="text-muted-foreground"
              disabled={worthlessMutation.isPending}
              onClick={() =>
                worthlessMutation.mutate(mapping.ticker, { onSuccess: () => setEditing(false) })
              }
            >
              <CircleSlash className="size-3.5" />
              {t('crypto.mappings.markWorthless', 'Marquer sans valeur (délistée)')}
            </Button>
          )}
        </div>
      )}

      {resolveMutation.isError && (
        <p className="mt-1 text-xs text-red-500">{extractErrorMessage(resolveMutation.error)}</p>
      )}
      {worthlessMutation.isError && (
        <p className="mt-1 text-xs text-red-500">{extractErrorMessage(worthlessMutation.error)}</p>
      )}
      {deleteMutation.isError && (
        <p className="mt-1 text-xs text-red-500">{extractErrorMessage(deleteMutation.error)}</p>
      )}
    </div>
  )
}
