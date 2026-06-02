import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ServerCrash } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ServerErrorPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const code = searchParams.get('code') || '500'

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="flex flex-col items-center gap-4 text-center max-w-sm mx-4">
        <ServerCrash className="size-12 text-destructive" />
        <h1 className="text-2xl font-bold">{code}</h1>
        <p className="text-muted-foreground">{t('error.serverError')}</p>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => navigate(-1)}>
            {t('common.back')}
          </Button>
          <Button onClick={() => navigate(0)}>{t('common.retry')}</Button>
        </div>
      </div>
    </div>
  )
}
