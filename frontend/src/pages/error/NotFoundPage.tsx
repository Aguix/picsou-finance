import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { FileQuestion } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="flex flex-col items-center gap-4 text-center max-w-sm mx-4">
        <FileQuestion className="size-12 text-muted-foreground" />
        <h1 className="text-2xl font-bold">404</h1>
        <p className="text-muted-foreground">{t('error.notFound')}</p>
        <Button asChild>
          <Link to="/">{t('error.backToDashboard')}</Link>
        </Button>
      </div>
    </div>
  )
}
