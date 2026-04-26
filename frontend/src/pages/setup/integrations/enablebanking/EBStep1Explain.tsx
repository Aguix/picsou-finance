import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Info, Landmark, Lock } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'

interface Props {
  onNext: () => void
}

export function EBStep1Explain({ onNext }: Props) {
  const { t } = useTranslation()
  const [waitingOnSignup, setWaitingOnSignup] = useState(false)
  const [psd2Open, setPsd2Open] = useState(false)

  const handleHaveAccount = () => onNext()

  const handleCreateAccount = () => {
    window.open('https://enablebanking.com/sign-up', '_blank', 'noopener,noreferrer')
    setWaitingOnSignup(true)
  }

  return (
    <EBSubstepShell current={1} total={5}>
      <div className="space-y-6">
        <div className="flex items-center justify-center gap-3 text-primary">
          <span className="rounded-xl bg-primary/10 p-3">
            <Landmark className="h-6 w-6" />
          </span>
          <span className="text-muted-foreground" aria-hidden="true">+</span>
          <span className="rounded-xl bg-primary/10 p-3">
            <Lock className="h-6 w-6" />
          </span>
        </div>

        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.explain.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.explain.body')}
          </p>
        </div>

        <div className="flex justify-center">
          <button
            type="button"
            onClick={() => setPsd2Open((v) => !v)}
            aria-expanded={psd2Open}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
          >
            <Info className="h-3.5 w-3.5" />
            {t('setup.enablebanking.explain.psd2Popover')}
          </button>
        </div>

        {psd2Open && (
          <div className="rounded-xl border border-border/60 bg-muted/40 p-4 text-xs text-muted-foreground">
            {t('setup.enablebanking.explain.psd2Body')}
          </div>
        )}

        {!waitingOnSignup ? (
          <div className="flex flex-col gap-3 sm:flex-row sm:justify-center">
            <Button
              variant="outline"
              size="lg"
              onClick={handleCreateAccount}
              className="w-full rounded-full sm:w-auto"
            >
              {t('setup.enablebanking.explain.needAccount')}
            </Button>
            <Button
              size="lg"
              onClick={handleHaveAccount}
              className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
            >
              {t('setup.enablebanking.explain.haveAccount')}
            </Button>
          </div>
        ) : (
          <div className="space-y-3 rounded-xl border border-border/60 bg-card p-4 text-center">
            <p className="text-sm text-muted-foreground">
              {t('setup.enablebanking.explain.signupOpened')}
            </p>
            <Button
              size="lg"
              onClick={onNext}
              className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
            >
              {t('setup.enablebanking.explain.continueAfterSignup')}
            </Button>
          </div>
        )}
      </div>
    </EBSubstepShell>
  )
}
