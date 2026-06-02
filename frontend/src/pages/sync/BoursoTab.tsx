import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { boursoApi } from '@/features/sync/api'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, User, Lock, ShieldCheck, AlertTriangle } from 'lucide-react'
import type { BoursoSessionStatus, BoursoAuthInitResponse } from '@/types/api'
import { extractErrorMessage, getErrorStatus, getErrorDetail } from '@/lib/errors'
import { formatDate } from '@/lib/utils'

type AuthState = 'IDLE' | 'AWAITING_MFA' | 'CONNECTED' | 'ERROR'

export function BoursoTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  const [authState, setAuthState]     = useState<AuthState>('IDLE')
  const [customerId, setCustomerId]   = useState('')
  const [password, setPassword]       = useState('')
  const [mfaCode, setMfaCode]         = useState('')
  const [processId, setProcessId]     = useState<string | null>(null)
  const [mfaInfo, setMfaInfo]         = useState<{ type: string; contact: string } | null>(null)
  const [errorMsg, setErrorMsg]       = useState<string | null>(null)

  const { data: sessionStatus, isLoading: statusLoading } = useQuery<BoursoSessionStatus>({
    queryKey: ['sync', 'bourso', 'status'],
    queryFn: boursoApi.getStatus,
  })

  const effectiveState: AuthState = sessionStatus?.isActive ? 'CONNECTED' : authState

  function formatError(error: unknown): string {
    const status = getErrorStatus(error)
    if (status === 429)  return t('sync.bourso.errors.tooManyAttempts')
    if (status === 401)  return t('sync.bourso.errors.invalidCredentials')
    const detail = (getErrorDetail(error) ?? '').toLowerCase()
    if (detail.includes('expired') || detail.includes('expiré'))
      return t('sync.bourso.errors.sessionExpired')
    if (detail.includes('mfa') || detail.includes('code'))
      return t('sync.bourso.errors.invalidMfaCode')
    if (status && status >= 500)   return t('sync.bourso.errors.serverError')
    return extractErrorMessage(error, t('sync.bourso.errors.serverError'))
  }

  const initiateMutation = useMutation({
    mutationFn: () => boursoApi.initiateAuth(customerId, password),
    onSuccess: (data: BoursoAuthInitResponse) => {
      setErrorMsg(null)
      if (!data.mfaRequired) {
        // Session stored server-side; refresh status
        queryClient.invalidateQueries({ queryKey: ['sync', 'bourso', 'status'] })
        queryClient.invalidateQueries({ queryKey: ['accounts'] })
        queryClient.invalidateQueries({ queryKey: ['dashboard'] })
        setAuthState('IDLE')
        setCustomerId('')
        setPassword('')
      } else {
        setProcessId(data.processId)
        setMfaInfo({ type: data.mfaType ?? 'MFA', contact: data.contact ?? '' })
        setAuthState('AWAITING_MFA')
      }
    },
    onError: (error: unknown) => {
      setErrorMsg(formatError(error))
      setAuthState('ERROR')
    },
  })

  const completeMfaMutation = useMutation({
    mutationFn: () => boursoApi.completeAuth(processId!, mfaCode),
    onSuccess: () => {
      setErrorMsg(null)
      setAuthState('IDLE')
      setMfaCode('')
      setProcessId(null)
      setMfaInfo(null)
      queryClient.invalidateQueries({ queryKey: ['sync', 'bourso', 'status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
    onError: (error: unknown) => {
      setErrorMsg(formatError(error))
      setAuthState('ERROR')
    },
  })

  const syncMutation = useMutation({
    mutationFn: boursoApi.sync,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const logoutMutation = useMutation({
    mutationFn: boursoApi.clearSession,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'bourso', 'status'] })
      setAuthState('IDLE')
      setCustomerId('')
      setPassword('')
      setMfaCode('')
      setProcessId(null)
      setMfaInfo(null)
    },
  })

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateMutation.mutate()
  }

  function handleMfa(e: React.FormEvent) {
    e.preventDefault()
    completeMfaMutation.mutate()
  }

  function handleRetry() {
    setErrorMsg(null)
    setAuthState('IDLE')
    setProcessId(null)
    setMfaCode('')
    setMfaInfo(null)
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center gap-3">
            {effectiveState === 'CONNECTED' ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t('sync.bourso.sessionActive')}
              </Badge>
            ) : (
              <Badge variant="outline">{t('sync.bourso.noSession')}</Badge>
            )}
            {effectiveState === 'CONNECTED' && sessionStatus?.expiresAt && (
              <span className="text-sm text-muted-foreground">
                {t('sync.bourso.expiresAt')} {formatDate(sessionStatus.expiresAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Error */}
      {errorMsg && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={handleRetry}>
                {t('common.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Connected */}
      {effectiveState === 'CONNECTED' && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            <RefreshCw />
            {t('sync.bourso.sync')}
          </Button>
          <Button variant="destructive" onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
            <LogOut />
            {t('sync.bourso.clearSession')}
          </Button>
        </div>
      )}

      {/* IDLE: login form */}
      {effectiveState === 'IDLE' && authState !== 'ERROR' && (
        <form onSubmit={handleInitiate} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="bourso-customer-id">
                  <User className="size-4 inline-block mr-1" />
                  {t('sync.bourso.customerId')}
                </Label>
                <Input
                  id="bourso-customer-id"
                  type="text"
                  inputMode="numeric"
                  autoComplete="username"
                  value={customerId}
                  onChange={e => setCustomerId(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="bourso-password">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.bourso.password')}
                </Label>
                <Input
                  id="bourso-password"
                  type="password"
                  inputMode="numeric"
                  autoComplete="current-password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={initiateMutation.isPending}>
                {t('sync.bourso.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {/* AWAITING_MFA: OTP code */}
      {authState === 'AWAITING_MFA' && (
        <form onSubmit={handleMfa} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              {mfaInfo && (
                <p className="text-sm text-muted-foreground">
                  {t('sync.bourso.mfaPrompt', { mfaType: mfaInfo.type, contact: mfaInfo.contact })}
                </p>
              )}
              <div className="space-y-2">
                <Label htmlFor="bourso-mfa">
                  <ShieldCheck className="size-4 inline-block mr-1" />
                  {t('sync.bourso.mfaCode')}
                </Label>
                <Input
                  id="bourso-mfa"
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  value={mfaCode}
                  onChange={e => setMfaCode(e.target.value)}
                  required
                />
              </div>
              <Button type="submit" disabled={completeMfaMutation.isPending}>
                {t('sync.bourso.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
