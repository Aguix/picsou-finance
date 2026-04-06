import { Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const demoMode = useAppStore(s => s.demoMode)

  if (demoMode) return <>{children}</>
  if (!isAuthenticated) return <Navigate to="/login" replace />

  return <>{children}</>
}

export function PublicOnly({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated)
  const demoMode = useAppStore(s => s.demoMode)

  if (demoMode || isAuthenticated) return <Navigate to="/" replace />

  return <>{children}</>
}
