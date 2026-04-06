import { createBrowserRouter, Navigate } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { RequireAuth, PublicOnly } from '@/features/auth/guards'
import { AppLayout } from '@/components/layout/AppLayout'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'

const LoginPage = lazy(() =>
  import('@/pages/login/LoginPage').then((m) => ({ default: m.LoginPage }))
)
const DashboardPage = lazy(() =>
  import('@/pages/dashboard/DashboardPage').then((m) => ({
    default: m.DashboardPage,
  }))
)
const AccountsPage = lazy(() =>
  import('@/pages/accounts/AccountsPage').then((m) => ({
    default: m.AccountsPage,
  }))
)
const AccountDetailPage = lazy(() =>
  import('@/pages/accounts/AccountDetailPage').then((m) => ({
    default: m.AccountDetailPage,
  }))
)
const GoalsPage = lazy(() =>
  import('@/pages/goals/GoalsPage').then((m) => ({ default: m.GoalsPage }))
)
const GoalCalendarPage = lazy(() =>
  import('@/pages/goals/GoalCalendarPage').then((m) => ({ default: m.GoalCalendarPage }))
)
const SyncPage = lazy(() =>
  import('@/pages/sync/SyncPage').then((m) => ({ default: m.SyncPage }))
)
const SettingsPage = lazy(() =>
  import('@/pages/settings/SettingsPage').then((m) => ({
    default: m.SettingsPage,
  }))
)

function SuspensePage({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<LoadingSkeleton />}>{children}</Suspense>
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: (
      <PublicOnly>
        <SuspensePage>
          <LoginPage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <SuspensePage><DashboardPage /></SuspensePage> },
      { path: 'accounts', element: <SuspensePage><AccountsPage /></SuspensePage> },
      { path: 'accounts/:id', element: <SuspensePage><AccountDetailPage /></SuspensePage> },
      { path: 'goals', element: <SuspensePage><GoalsPage /></SuspensePage> },
      { path: 'goals/:id/calendar', element: <SuspensePage><GoalCalendarPage /></SuspensePage> },
      { path: 'sync', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'sync/callback', element: <SuspensePage><SyncPage /></SuspensePage> },
      { path: 'settings', element: <SuspensePage><SettingsPage /></SuspensePage> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
