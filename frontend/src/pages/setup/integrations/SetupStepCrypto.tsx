import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Bitcoin, CheckCircle2, CircleAlert, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useGenerateCryptoKey } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

type Phase = 'idle' | 'running' | 'existed' | 'created' | 'error'

/**
 * The happy path in Docker-land is "key already exists" — the entrypoint
 * wrote it before Spring even booted, so the backend responds with
 * `existed: true` and we immediately mark the integration done. The UI
 * still shows the idle-then-action flow because bare-metal installs land
 * in the `created: true` branch.
 */
export function SetupStepCrypto() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const gen = useGenerateCryptoKey()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const [phase, setPhase] = useState<Phase>('idle')
  const [keyPath, setKeyPath] = useState<string | null>(null)

  const run = () => {
    setPhase('running')
    gen.mutate(undefined, {
      onSuccess: (result) => {
        setKeyPath(result.path)
        setPhase(result.existed ? 'existed' : 'created')
        markDone('crypto')
      },
      onError: () => setPhase('error'),
    })
  }

  /**
   * Auto-fire on mount: most self-hosters land here with the key already
   * created by the Docker entrypoint, so eagerly triggering the idempotent
   * endpoint gives them a single-click "Continue" experience. Bare-metal
   * users will still see the regular success state after ~100ms.
   */
  useEffect(() => {
    run()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const proceed = () => navigate(nextIntegrationRoute('crypto', selected))
  const skip = () => navigate(nextIntegrationRoute('crypto', selected))

  const successCopy =
    phase === 'existed'
      ? t('setup.crypto.existed')
      : phase === 'created'
        ? t('setup.crypto.created')
        : ''

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <p className="text-xs font-semibold tracking-[0.2em] text-muted-foreground">
          {t('setup.crypto.surtitle')}
        </p>
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <Bitcoin className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.crypto.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.crypto.body')}
        </p>
      </div>

      {(phase === 'idle' || phase === 'running') && (
        <div className="flex flex-col items-center gap-2 py-6 text-muted-foreground">
          <Loader2 className="h-6 w-6 animate-spin" />
          <p className="text-sm">{t('setup.crypto.generating')}</p>
        </div>
      )}

      {(phase === 'existed' || phase === 'created') && (
        <div className="space-y-3 rounded-2xl border border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
          <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
          <p className="text-lg font-medium">{successCopy}</p>
          {keyPath && (
            <p className="text-xs text-muted-foreground">
              {t('setup.crypto.pathLabel')}{' '}
              <span className="font-mono">{keyPath}</span>
            </p>
          )}
        </div>
      )}

      {phase === 'error' && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-6"
        >
          <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
          <p className="text-sm text-destructive">{t('setup.crypto.error')}</p>
        </div>
      )}

      <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
        <Button
          type="button"
          variant="ghost"
          onClick={skip}
          className="w-full sm:w-auto"
        >
          {t('setup.crypto.skip')}
        </Button>
        {phase === 'error' ? (
          <Button
            size="lg"
            onClick={run}
            disabled={gen.isPending}
            className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
          >
            {t('setup.crypto.generateCta')}
          </Button>
        ) : (
          <Button
            size="lg"
            onClick={proceed}
            disabled={phase === 'running'}
            className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
          >
            {t('setup.crypto.continue')}
          </Button>
        )}
      </div>
    </div>
  )
}
