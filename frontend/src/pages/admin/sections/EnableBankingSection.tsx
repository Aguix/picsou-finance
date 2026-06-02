import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Landmark } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { extractErrorMessage } from '@/lib/errors'
import { useUpdateEnableBanking } from '@/features/admin/hooks'
import type { AdminEnableBankingSettings } from '@/features/admin/api'

const schema = z.object({
  applicationId: z.string().min(1),
  keyId: z.string().min(1),
  redirectUri: z.string().url(),
})

type FormValues = z.infer<typeof schema>

export function EnableBankingSection({ settings }: { settings: AdminEnableBankingSettings }) {
  const { t } = useTranslation()
  const update = useUpdateEnableBanking()

  const { register, handleSubmit, reset, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: settings,
  })

  useEffect(() => { reset(settings) }, [settings, reset])

  const onSubmit = handleSubmit(async (values) => {
    await update.mutateAsync(values)
  })

  const FIELDS: { name: keyof FormValues; labelKey: string; placeholder?: string }[] = [
    { name: 'applicationId', labelKey: 'admin.enableBanking.applicationId' },
    { name: 'keyId', labelKey: 'admin.enableBanking.keyId' },
    { name: 'redirectUri', labelKey: 'admin.enableBanking.redirectUri', placeholder: 'https://app.com/sync/callback' },
  ]

  return (
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Landmark className="size-5 text-muted-foreground" />
          {t('admin.enableBanking.title')}
        </CardTitle>
        <CardDescription>{t('admin.enableBanking.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          {FIELDS.map(({ name, labelKey, placeholder }) => (
            <div key={name} className="space-y-1.5">
              <Label htmlFor={`admin-eb-${name}`}>{t(labelKey)}</Label>
              <Input
                id={`admin-eb-${name}`}
                placeholder={placeholder}
                aria-invalid={!!formState.errors[name]}
                {...register(name)}
              />
              {formState.errors[name] && (
                <p className="text-xs text-destructive">{formState.errors[name]?.message}</p>
              )}
            </div>
          ))}

          {update.error && (
            <p role="alert" className="text-sm text-destructive">
              {extractErrorMessage(update.error)}
            </p>
          )}

          <Button type="submit" disabled={update.isPending || !formState.isDirty}>
            {update.isPending ? t('admin.enableBanking.saving') : t('admin.enableBanking.save')}
          </Button>
          {update.isSuccess && !formState.isDirty && (
            <span className="ml-3 text-sm text-emerald-600">{t('admin.enableBanking.saved')}</span>
          )}
        </form>
      </CardContent>
    </Card>
  )
}
