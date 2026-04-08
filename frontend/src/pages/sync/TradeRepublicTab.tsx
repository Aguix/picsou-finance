import { useState, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api-client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import {
  RefreshCw,
  Upload,
  LogOut,
  Smartphone,
  Lock,
  ShieldCheck,
  AlertTriangle,
} from 'lucide-react'
import type { TrSessionStatus } from '@/types/api'

type AuthState = 'IDLE' | 'AWAITING_TAN' | 'CONNECTED' | 'ERROR'

export function TradeRepublicTab() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)

  const formatAuthError = (error: any): string => {
    // Rate limit error
    if (error.response?.status === 429) {
      return t('sync.tr.errors.tooManyAttempts')
    }

    // Bad gateway or TR rejection
    if (error.response?.status === 502) {
      const detail = error.response?.data?.detail || ''

      // Try to extract specific TR error
      if (detail.includes('NUMBER_INVALID')) {
        return t('sync.tr.errors.invalidPhoneNumber')
      }
      if (detail.includes('PIN_INVALID')) {
        return t('sync.tr.errors.invalidPin')
      }
      if (detail.includes('AUTHENTICATION_ERROR')) {
        return t('sync.tr.errors.authenticationFailed')
      }

      return t('sync.tr.errors.serverError')
    }

    // Validation errors (422)
    if (error.response?.status === 422) {
      const errors = error.response?.data?.errors || {}
      if (errors.phoneNumber) {
        return t('sync.tr.errors.phoneNumberRequired')
      }
      if (errors.pin) {
        return t('sync.tr.errors.pinRequired')
      }
      return t('sync.tr.errors.validationFailed')
    }

    // Fallback
    return error.message || t('sync.tr.errors.unknownError')
  }

  const [authState, setAuthState] = useState<AuthState>('IDLE')
  const [phone, setPhone] = useState('')
  const [pin, setPin] = useState('')
  const [tan, setTan] = useState('')
  const [processId, setProcessId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const { data: sessionStatus, isLoading: statusLoading } = useQuery<TrSessionStatus>({
    queryKey: ['sync', 'tr', 'status'],
    queryFn: () => api.get<TrSessionStatus>('/tr/status').then(r => r.data),
  })

  // Derive auth state from session status
  const effectiveState = sessionStatus?.isActive ? 'CONNECTED' : authState

  const initiateMutation = useMutation({
    mutationFn: (params: { phone: string; pin: string }) =>
      api.post<{ processId: string }>('/tr/auth/initiate', { phoneNumber: params.phone, pin: params.pin }).then(r => r.data),
    onSuccess: (data) => {
      setProcessId(data.processId)
      setAuthState('AWAITING_TAN')
      setErrorMsg(null)
    },
    onError: (error: any) => {
      const friendlyMsg = formatAuthError(error)
      setErrorMsg(friendlyMsg)
      setAuthState('ERROR')
    },
  })

  const submitTanMutation = useMutation({
    mutationFn: (tanCode: string) =>
      api.post('/tr/auth/complete', { processId: processId!, tan: tanCode }).then(r => r.data),
    onSuccess: () => {
      setAuthState('IDLE')
      setTan('')
      setPhone('')
      setPin('')
      setProcessId(null)
      setErrorMsg(null)
      queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
    },
    onError: (error: any) => {
      const friendlyMsg = formatAuthError(error)
      setErrorMsg(friendlyMsg)
      setAuthState('ERROR')
    },
  })

  const syncMutation = useMutation({
    mutationFn: () => api.post('/tr/sync').then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const importCsvMutation = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api.post('/tr/import', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      }).then(r => r.data)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      if (fileInputRef.current) fileInputRef.current.value = ''
    },
  })

  const logoutMutation = useMutation({
    mutationFn: () => api.delete('/tr/session').then(r => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sync', 'tr', 'status'] })
      setAuthState('IDLE')
      setPhone('')
      setPin('')
      setTan('')
      setProcessId(null)
    },
  })

  function handleInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateMutation.mutate({ phone, pin })
  }

  function handleTan(e: React.FormEvent) {
    e.preventDefault()
    submitTanMutation.mutate(tan)
  }

  function handleCsvChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) importCsvMutation.mutate(file)
  }

  function handleRetry() {
    setErrorMsg(null)
    if (authState === 'ERROR') {
      setAuthState('IDLE')
      setProcessId(null)
      setTan('')
    }
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {effectiveState === 'CONNECTED' ? (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.tr.sessionActive')}
                </Badge>
              ) : (
                <Badge variant="outline">
                  {t('sync.tr.noSession')}
                </Badge>
              )}
              {effectiveState === 'CONNECTED' && sessionStatus?.expiresAt && (
                <span className="text-sm text-muted-foreground">
                  {t('sync.tr.expiresAt')} {new Date(sessionStatus.expiresAt).toLocaleString()}
                </span>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Error state */}
      {(authState === 'ERROR' || errorMsg) && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={handleRetry}>
                {t('sync.banks.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Connected controls */}
      {effectiveState === 'CONNECTED' && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => syncMutation.mutate()} disabled={syncMutation.isPending}>
            <RefreshCw />
            {t('sync.tr.sync')}
          </Button>

          <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={importCsvMutation.isPending}>
            <Upload />
            {t('sync.tr.importCsv')}
          </Button>

          <Button variant="destructive" onClick={() => logoutMutation.mutate()} disabled={logoutMutation.isPending}>
            <LogOut />
            {t('sync.tr.clearSession')}
          </Button>

          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleCsvChange}
          />
        </div>
      )}

      {/* IDLE: Phone + PIN form */}
      {effectiveState === 'IDLE' && authState !== 'ERROR' && (
        <form onSubmit={handleInitiate} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="tr-phone">
                  <Smartphone className="size-4 inline-block mr-1" />
                  {t('sync.tr.phone')}
                </Label>
                <Input
                  id="tr-phone"
                  type="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  required
                  placeholder="+49..."
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="tr-pin">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.tr.pin')}
                </Label>
                <Input
                  id="tr-pin"
                  type="password"
                  value={pin}
                  onChange={(e) => setPin(e.target.value)}
                  required
                />
              </div>

              <Button type="submit" disabled={initiateMutation.isPending} className="w-full">
                {t('sync.tr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      {/* AWAITING_TAN: TAN input */}
      {effectiveState === 'AWAITING_TAN' && (
        <form onSubmit={handleTan} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="tr-tan">
                  <ShieldCheck className="size-4 inline-block mr-1" />
                  {t('sync.tr.tan')}
                </Label>
                <Input
                  id="tr-tan"
                  value={tan}
                  onChange={(e) => setTan(e.target.value)}
                  required
                  autoFocus
                />
              </div>

              <Button type="submit" disabled={submitTanMutation.isPending} className="w-full">
                {t('sync.tr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
