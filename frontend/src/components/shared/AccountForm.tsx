import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ColorPicker } from '@/components/shared/ColorPicker'
import { ACCOUNT_TYPES } from '@/lib/constants'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

const accountSchema = z.object({
  name: z.string().min(1).max(100),
  type: z.enum(['LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS', 'OTHER']),
  provider: z.string().max(100).optional(),
  currency: z.string().min(1).max(10),
  currentBalance: z.number().min(0).optional(),
  isManual: z.boolean(),
  color: z.string(),
  ticker: z.string().max(20).optional(),
})

type AccountFormData = z.infer<typeof accountSchema>

interface AccountFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (data: AccountFormData) => void
  defaultValues?: Partial<AccountFormData>
  title?: string
  loading?: boolean
}

export function AccountForm({ open, onOpenChange, onSubmit, defaultValues, title, loading }: AccountFormProps) {
  const { t } = useTranslation()
  const { register, handleSubmit, watch, setValue, reset } = useForm<AccountFormData>({
    resolver: zodResolver(accountSchema),
    defaultValues: {
      name: '',
      type: 'CHECKING',
      provider: '',
      currency: 'EUR',
      currentBalance: undefined,
      isManual: false,
      color: '#6366f1',
      ticker: '',
      ...defaultValues,
    },
  })

  const selectedColor = watch('color')

  function handleFormSubmit(data: AccountFormData) {
    onSubmit(data)
  }

  // Reset form when dialog opens
  function handleOpenChange(open: boolean) {
    if (open) {
      reset({
        name: '',
        type: 'CHECKING',
        provider: '',
        currency: 'EUR',
        currentBalance: undefined,
        isManual: false,
        color: '#6366f1',
        ticker: '',
        ...defaultValues,
      })
    }
    onOpenChange(open)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{title ?? t('accounts.addAccount')}</DialogTitle>
          <DialogDescription />
        </DialogHeader>
        <form onSubmit={handleSubmit(handleFormSubmit)} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">{t('accounts.addAccount')}</Label>
            <Input id="name" {...register('name')} placeholder="PEA Boursorama" />
          </div>

          <div className="space-y-2">
            <Label htmlFor="type">{t('accounts.allTypes')}</Label>
            <select
              id="type"
              {...register('type')}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring"
            >
              {ACCOUNT_TYPES.map((at) => (
                <option key={at.value} value={at.value}>
                  {t(at.labelKey)}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="currency">Devise</Label>
              <Input id="currency" {...register('currency')} placeholder="EUR" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="balance">{t('accounts.balance')}</Label>
              <Input id="balance" type="number" step="0.01" {...register('currentBalance', { valueAsNumber: true })} />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="provider">Provider</Label>
              <Input id="provider" {...register('provider')} placeholder="Boursorama" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="ticker">Ticker</Label>
              <Input id="ticker" {...register('ticker')} placeholder="BTC" />
            </div>
          </div>

          <div className="space-y-2">
            <Label>Couleur</Label>
            <ColorPicker value={selectedColor} onChange={(c) => setValue('color', c)} />
          </div>

          <div className="flex items-center gap-2">
            <input id="isManual" type="checkbox" {...register('isManual')} className="h-4 w-4 rounded" />
            <Label htmlFor="isManual">{t('accounts.manual')}</Label>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={loading}>
              {loading ? t('common.loading') : t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
