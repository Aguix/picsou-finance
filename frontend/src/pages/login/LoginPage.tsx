import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useLogin } from '@/features/auth/hooks'
import { useAppStore } from '@/stores/app-store'
import { safeRedirect } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Eye, EyeOff, Loader2 } from 'lucide-react'

export function LoginPage() {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const loginMutation = useLogin()
  const demoMode = useAppStore(s => s.demoMode)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await loginMutation.mutateAsync({ username, password })
      navigate(redirect, { replace: true })
    } catch {
      setError(t('auth.error'))
    }
  }

  return (
    <div className="min-h-screen bg-background flex items-center justify-center">
      <div className="max-w-sm w-full mx-4 flex flex-col gap-4">
        <Card>
          <CardHeader className="items-center text-center">
            <CardTitle className="text-xl">{t('auth.login')}</CardTitle>
            <CardDescription className="mt-0.5">{t('auth.loginTagline', 'Picsou')}</CardDescription>
          </CardHeader>

          <CardContent>
            <form onSubmit={handleSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="username">{t('auth.username')}</Label>
                <Input
                  id="username"
                  type="text"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                  placeholder="admin"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="password">{t('auth.password')}</Label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPw ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                    placeholder="••••••••"
                    className="pr-9"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPw(v => !v)}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground transition-colors"
                    aria-label={showPw ? t('auth.hidePassword', 'Hide password') : t('auth.showPassword', 'Show password')}
                  >
                    {showPw ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              {error && (
                <p className="text-sm font-medium text-destructive">{error}</p>
              )}

              <Button type="submit" disabled={loginMutation.isPending} className="w-full mt-1">
                {loginMutation.isPending && (
                  <Loader2 size={16} className="animate-spin" />
                )}
                {loginMutation.isPending ? t('auth.loggingIn') : t('auth.loginButton')}
              </Button>
            </form>
          </CardContent>
        </Card>

        {demoMode && (
          <Card className="border-muted bg-muted/40">
            <CardContent className="pt-4 pb-4">
              <p className="text-sm font-medium text-foreground">{t('auth.demoMode')}</p>
              <p className="text-xs text-muted-foreground mt-0.5">{t('auth.demoModeDesc')}</p>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
