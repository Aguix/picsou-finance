import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Coins, KeyRound, ListChecks, Loader2, Plus, Trash2 } from 'lucide-react'
import { AssetRegistryModal } from '@/components/shared/AssetRegistryModal'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { extractErrorMessage } from '@/lib/errors'
import {
  useAggregators,
  useToggleAggregator,
  useCreateAggregatorSession,
  useToggleAggregatorSession,
  useDeleteAggregatorSession,
} from '@/features/admin/hooks'
import type { AggregatorView, AggregatorSessionView } from '@/features/admin/api'

export function AggregatorsSection() {
  const { t } = useTranslation()
  const { data, isLoading } = useAggregators()
  const [showRegistry, setShowRegistry] = useState(false)

  return (
    <Card className="rounded-4xl bg-card">
      <CardHeader>
        <div className="flex items-start justify-between gap-2">
          <div>
            <CardTitle className="flex items-center gap-2 text-lg">
              <Coins className="size-5 text-muted-foreground" />
              {t('admin.aggregators.title')}
            </CardTitle>
            <CardDescription>{t('admin.aggregators.description')}</CardDescription>
          </div>
          <Button type="button" variant="outline" size="sm" onClick={() => setShowRegistry(true)}>
            <ListChecks className="mr-1 size-4" />
            {t('assets.registry.button')}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading && <Loader2 className="size-5 animate-spin text-muted-foreground" />}
        {data?.map((aggregator) => (
          <AggregatorRow key={aggregator.key} aggregator={aggregator} />
        ))}
      </CardContent>
      <AssetRegistryModal open={showRegistry} onOpenChange={setShowRegistry} />
    </Card>
  )
}

function AggregatorRow({ aggregator }: { aggregator: AggregatorView }) {
  const { t } = useTranslation()
  const toggleAggregator = useToggleAggregator()

  return (
    <div className="rounded-2xl border border-border/60 p-4">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-medium">{aggregator.displayName}</p>
          <p className="text-xs text-muted-foreground">
            {aggregator.sessions.length === 0
              ? t('admin.aggregators.noKeys')
              : t('admin.aggregators.keyCount', { count: aggregator.sessions.length })}
          </p>
        </div>
        <Switch
          checked={aggregator.enabled}
          disabled={toggleAggregator.isPending}
          onCheckedChange={(enabled) =>
            toggleAggregator.mutate({ key: aggregator.key, enabled })
          }
          aria-label={aggregator.displayName}
        />
      </div>

      {aggregator.sessions.length > 0 && (
        <ul className="mt-3 space-y-2">
          {aggregator.sessions.map((session) => (
            <SessionRow key={session.id} session={session} />
          ))}
        </ul>
      )}

      <AddKeyForm aggregatorKey={aggregator.key} />
    </div>
  )
}

function SessionRow({ session }: { session: AggregatorSessionView }) {
  const { t } = useTranslation()
  const toggleSession = useToggleAggregatorSession()
  const deleteSession = useDeleteAggregatorSession()

  return (
    <li className="flex items-center justify-between gap-2 rounded-xl bg-muted/40 px-3 py-2">
      <div className="flex min-w-0 items-center gap-2">
        <KeyRound className="size-4 shrink-0 text-muted-foreground" />
        <span className="truncate text-sm">
          {session.label || t('admin.aggregators.unlabeledKey', { id: session.id })}
        </span>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Switch
          checked={session.enabled}
          disabled={toggleSession.isPending}
          onCheckedChange={(enabled) => toggleSession.mutate({ id: session.id, enabled })}
          aria-label={t('admin.aggregators.toggleKey')}
        />
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="size-8 text-muted-foreground hover:text-destructive"
          disabled={deleteSession.isPending}
          onClick={() => deleteSession.mutate(session.id)}
          aria-label={t('admin.aggregators.deleteKey')}
        >
          <Trash2 className="size-4" />
        </Button>
      </div>
    </li>
  )
}

function AddKeyForm({ aggregatorKey }: { aggregatorKey: string }) {
  const { t } = useTranslation()
  const create = useCreateAggregatorSession()
  const [label, setLabel] = useState('')
  const [apiKey, setApiKey] = useState('')

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!apiKey.trim()) return
    create.mutate(
      { key: aggregatorKey, body: { label: label.trim() || undefined, apiKey: apiKey.trim() } },
      { onSuccess: () => { setLabel(''); setApiKey('') } },
    )
  }

  return (
    <form onSubmit={submit} className="mt-3 flex flex-col gap-2 sm:flex-row">
      <Input
        value={label}
        onChange={(e) => setLabel(e.target.value)}
        placeholder={t('admin.aggregators.labelPlaceholder')}
        className="sm:max-w-[12rem]"
        aria-label={t('admin.aggregators.labelPlaceholder')}
      />
      <Input
        value={apiKey}
        onChange={(e) => setApiKey(e.target.value)}
        placeholder={t('admin.aggregators.keyPlaceholder')}
        autoComplete="off"
        className="flex-1 font-mono"
        aria-label={t('admin.aggregators.keyPlaceholder')}
      />
      <Button type="submit" variant="outline" disabled={!apiKey.trim() || create.isPending}>
        {create.isPending ? (
          <Loader2 className="size-4 animate-spin" />
        ) : (
          <><Plus className="mr-1 size-4" />{t('admin.aggregators.addKey')}</>
        )}
      </Button>
      {create.isError && (
        <p role="alert" className="text-sm text-destructive sm:self-center">
          {extractErrorMessage(create.error)}
        </p>
      )}
    </form>
  )
}
