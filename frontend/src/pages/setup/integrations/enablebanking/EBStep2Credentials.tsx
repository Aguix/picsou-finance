import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { EBSubstepShell } from './EBSubstepShell'
import {
  enableBankingConfigSchema,
  type EnableBankingConfigFormValues,
} from '@/features/setup/schemas'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { useWriteEnableBankingConfig } from '@/features/setup/hooks'

interface Props {
  onNext: () => void
  onBack: () => void
}

/**
 * Credentials-only. The "redirectUri" portion of the EB config schema is
 * derived automatically in step 4 — we post the full config once at step 4
 * to avoid shipping it twice. Here we just validate the two UUIDs, stash
 * them in the Zustand draft, and advance.
 */
export function EBStep2Credentials({ onNext, onBack }: Props) {
  const { t } = useTranslation()
  const draft = useSetupFlowStore((s) => s.ebDraft)
  const updateEbDraft = useSetupFlowStore((s) => s.updateEbDraft)
  const writeConfig = useWriteEnableBankingConfig()

  const [serverError, setServerError] = useState<string | null>(null)

  const defaultRedirect =
    typeof window !== 'undefined' ? `${window.location.origin}/sync/callback` : ''

  const { register, handleSubmit, formState, setValue } = useForm<EnableBankingConfigFormValues>({
    resolver: zodResolver(enableBankingConfigSchema),
    defaultValues: {
      applicationId: draft.applicationId ?? '',
      keyId: draft.keyId ?? '',
      redirectUri: draft.redirectUri || defaultRedirect,
    },
    mode: 'onBlur',
  })

  /**
   * Trim clipboard whitespace on paste — users copy UUIDs from dashboards
   * that love adding a trailing newline. Trimming here (instead of in
   * onChange) avoids eating legitimate typed characters.
   */
  const handlePaste =
    (name: 'applicationId' | 'keyId') =>
    (e: React.ClipboardEvent<HTMLInputElement>) => {
      const pasted = e.clipboardData.getData('text').trim()
      if (pasted) {
        e.preventDefault()
        setValue(name, pasted, { shouldValidate: true })
      }
    }

  useEffect(() => {
    // Pre-populate draft with the autodiscovered redirect URI so step 4
    // can pick it up without re-computing if the user navigated back.
    if (!draft.redirectUri && defaultRedirect) {
      updateEbDraft({ redirectUri: defaultRedirect })
    }
  }, [draft.redirectUri, defaultRedirect, updateEbDraft])

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    updateEbDraft({
      applicationId: values.applicationId.trim(),
      keyId: values.keyId.trim(),
      redirectUri: values.redirectUri,
    })
    try {
      await writeConfig.mutateAsync({
        applicationId: values.applicationId.trim(),
        keyId: values.keyId.trim(),
        redirectUri: values.redirectUri,
      })
      onNext()
    } catch (err) {
      const detail =
        (err as { response?: { data?: { detail?: string } } })?.response?.data?.detail ??
        String(err)
      setServerError(detail)
    }
  })

  return (
    <EBSubstepShell current={2} total={5}>
      <form onSubmit={onSubmit} noValidate className="space-y-6">
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.creds.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.creds.body')}
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="eb-app-id">{t('setup.enablebanking.creds.applicationId')}</Label>
          <Input
            id="eb-app-id"
            autoComplete="off"
            spellCheck={false}
            placeholder="00000000-0000-0000-0000-000000000000"
            aria-invalid={!!formState.errors.applicationId}
            onPaste={handlePaste('applicationId')}
            {...register('applicationId')}
          />
          {formState.errors.applicationId && (
            <p className="text-xs text-destructive">
              {t(formState.errors.applicationId.message ?? 'setup.enablebanking.appIdFormat')}
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="eb-key-id">{t('setup.enablebanking.creds.keyId')}</Label>
          <Input
            id="eb-key-id"
            autoComplete="off"
            spellCheck={false}
            placeholder="00000000-0000-0000-0000-000000000000"
            aria-invalid={!!formState.errors.keyId}
            onPaste={handlePaste('keyId')}
            {...register('keyId')}
          />
          {formState.errors.keyId && (
            <p className="text-xs text-destructive">
              {t(formState.errors.keyId.message ?? 'setup.enablebanking.keyIdFormat')}
            </p>
          )}
        </div>

        {/* Hidden redirectUri — validated but not user-editable here; step 4
            shows it clearly and the user can re-confirm there. */}
        <input type="hidden" {...register('redirectUri')} />

        {serverError && (
          <p role="alert" className="text-sm text-destructive">
            {serverError}
          </p>
        )}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            onClick={onBack}
            className="w-full sm:w-auto"
          >
            {t('setup.enablebanking.back')}
          </Button>
          <Button
            type="submit"
            size="lg"
            disabled={writeConfig.isPending || !formState.isValid}
            className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
          >
            {t('setup.enablebanking.continue')}
          </Button>
        </div>
      </form>
    </EBSubstepShell>
  )
}
