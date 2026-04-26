import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { setupAdminSchema, type SetupAdminFormValues } from '@/features/setup/schemas'
import { useSubmitAdmin } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { stashSetupCredentials } from '@/stores/setup-credentials'

/**
 * Palette of 6 avatar colors pulled from Tailwind's semantic tokens.
 * Hex is used so the DB field (which stores raw hex) and this picker
 * agree on representation. Kept intentionally small — more swatches
 * just means more decision fatigue on a step users should breeze through.
 */
const AVATAR_SWATCHES = [
  '#6366f1', // indigo-500
  '#ec4899', // pink-500
  '#10b981', // emerald-500
  '#f59e0b', // amber-500
  '#ef4444', // red-500
  '#3b82f6', // blue-500
]

function scorePassword(pw: string): 0 | 1 | 2 | 3 | 4 {
  let score = 0
  if (pw.length >= 10) score++
  if (pw.length >= 14) score++
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++
  if (/\d/.test(pw) && /[^a-zA-Z0-9]/.test(pw)) score++
  return Math.min(score, 4) as 0 | 1 | 2 | 3 | 4
}

const STRENGTH_COPY: Record<0 | 1 | 2 | 3 | 4, { key: string; tone: string }> = {
  0: { key: 'setup.admin.strength.weak', tone: 'bg-destructive' },
  1: { key: 'setup.admin.strength.weak', tone: 'bg-destructive' },
  2: { key: 'setup.admin.strength.fair', tone: 'bg-amber-500' },
  3: { key: 'setup.admin.strength.good', tone: 'bg-emerald-500' },
  4: { key: 'setup.admin.strength.strong', tone: 'bg-emerald-500' },
}

export function SetupStepAdmin() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const setAdminDisplayName = useSetupFlowStore((s) => s.setAdminDisplayName)
  const submitAdmin = useSubmitAdmin()

  const randomDefault = useMemo(
    () => AVATAR_SWATCHES[Math.floor(Math.random() * AVATAR_SWATCHES.length)],
    []
  )

  const { register, handleSubmit, watch, setValue, formState } = useForm<SetupAdminFormValues>({
    resolver: zodResolver(setupAdminSchema),
    defaultValues: {
      username: '',
      password: '',
      displayName: '',
      avatarColor: randomDefault,
    },
    mode: 'onBlur',
  })

  const password = watch('password')
  const avatarColor = watch('avatarColor')
  const score = scorePassword(password ?? '')
  const strength = STRENGTH_COPY[score]

  const onSubmit = handleSubmit(async (values) => {
    const displayName = values.displayName?.trim() || values.username
    await submitAdmin.mutateAsync({
      username: values.username,
      password: values.password,
      displayName,
      avatarColor: values.avatarColor,
    })
    setAdminDisplayName(displayName)
    stashSetupCredentials(values.username, values.password)
    navigate('/setup/security')
  })

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <p className="text-xs font-semibold tracking-[0.2em] text-muted-foreground">
          {t('setup.admin.surtitle')}
        </p>
        <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight">
          {t('setup.admin.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.admin.subtitle')}
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-5" noValidate>
        <div className="space-y-1.5">
          <Label htmlFor="setup-username">{t('setup.admin.username')}</Label>
          <Input
            id="setup-username"
            autoComplete="username"
            autoFocus
            aria-invalid={!!formState.errors.username}
            {...register('username')}
          />
          <p className="text-xs text-muted-foreground">
            {formState.errors.username
              ? t(formState.errors.username.message ?? 'setup.admin.usernameHint')
              : t('setup.admin.usernameHint')}
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="setup-password">{t('setup.admin.password')}</Label>
          <Input
            id="setup-password"
            type="password"
            autoComplete="new-password"
            aria-invalid={!!formState.errors.password}
            {...register('password')}
          />
          <div className="flex gap-1" aria-hidden="true">
            {[0, 1, 2, 3].map((i) => (
              <span
                key={i}
                className={cn(
                  'h-1 flex-1 rounded-full bg-muted transition-colors',
                  score > i && strength.tone
                )}
              />
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            {password
              ? t(strength.key)
              : formState.errors.password
                ? t(formState.errors.password.message ?? 'setup.admin.passwordHint')
                : t('setup.admin.passwordHint')}
          </p>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="setup-display-name">{t('setup.admin.displayName')}</Label>
          <Input
            id="setup-display-name"
            autoComplete="name"
            {...register('displayName')}
          />
          <p className="text-xs text-muted-foreground">
            {t('setup.admin.displayNameHint')}
          </p>
        </div>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">{t('setup.admin.avatarColor')}</legend>
          <div className="flex flex-wrap gap-3">
            {AVATAR_SWATCHES.map((hex) => {
              const active = avatarColor === hex
              return (
                <button
                  key={hex}
                  type="button"
                  onClick={() => setValue('avatarColor', hex, { shouldValidate: true })}
                  aria-label={hex}
                  aria-pressed={active}
                  className={cn(
                    'h-10 w-10 rounded-full border-2 transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
                    active ? 'border-foreground' : 'border-transparent'
                  )}
                  style={{ backgroundColor: hex }}
                />
              )
            })}
          </div>
        </fieldset>

        {submitAdmin.error && (
          <p role="alert" className="text-sm text-destructive">
            {(submitAdmin.error as { response?: { data?: { detail?: string } } })?.response?.data
              ?.detail ?? String(submitAdmin.error)}
          </p>
        )}

        <div className="pt-2">
          <Button
            type="submit"
            size="lg"
            disabled={submitAdmin.isPending || !formState.isValid}
            className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
          >
            {submitAdmin.isPending ? t('setup.admin.creating') : t('setup.admin.cta')}
          </Button>
        </div>
      </form>
    </div>
  )
}
