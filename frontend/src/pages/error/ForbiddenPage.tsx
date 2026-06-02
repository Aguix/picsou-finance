import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ShieldX } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ForbiddenPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="flex flex-col items-center gap-4 text-center max-w-sm mx-4">
        <ShieldX className="size-12 text-destructive" />
        <h1 className="text-2xl font-bold">403</h1>
        <p className="text-muted-foreground">{t('error.forbidden')}</p>
        <Button variant="outline" onClick={() => navigate(-1)}>
          {t('common.back')}
        </Button>
      </div>
    </div>
  )
}
