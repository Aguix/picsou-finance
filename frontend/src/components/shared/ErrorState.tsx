import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface ErrorStateProps {
  title?: string
  message?: string
  onRetry?: () => void
}

export function ErrorState({ title = 'Error', message, onRetry }: ErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <AlertTriangle className="size-10 text-destructive mb-4" />
      <h3 className="text-lg font-medium">{title}</h3>
      {message && (
        <p className="mt-1 text-sm text-muted-foreground">{message}</p>
      )}
      {onRetry && (
        <Button variant="outline" onClick={onRetry} className="mt-4">
          Retry
        </Button>
      )}
    </div>
  )
}
