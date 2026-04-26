import { createBrowserRouter } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { RequireAuth, PublicOnly, RequireAdmin } from '@/features/auth/guards'
import { RequireSetup, SetupOnly } from '@/features/setup/guards'
import { AppLayout } from '@/components/layout/AppLayout'
import { LoadingSkeleton } from '@/components/shared/LoadingSkeleton'
import '@/pages/setup/setup.css'

const LoginPage = lazy(() =>
  import('@/pages/login/LoginPage').then((m) => ({ default: m.LoginPage }))
)
const MfaChallengePage = lazy(() =>
  import('@/pages/login/MfaChallengePage').then((m) => ({ default: m.MfaChallengePage }))
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

const ActivationPage = lazy(() =>
  import('@/pages/activation/ActivationPage').then((m) => ({ default: m.ActivationPage }))
)
const FamilyDashboardPage = lazy(() =>
  import('@/pages/family/FamilyDashboardPage').then((m) => ({ default: m.FamilyDashboardPage }))
)
const FamilySettingsPage = lazy(() =>
  import('@/pages/settings/FamilySettingsPage').then((m) => ({ default: m.FamilySettingsPage }))
)
const AdminPage = lazy(() =>
  import('@/pages/admin/AdminPage').then((m) => ({ default: m.AdminPage }))
)

const SetupLayout = lazy(() =>
  import('@/pages/setup/SetupLayout').then((m) => ({ default: m.SetupLayout }))
)
const SetupStepIntro = lazy(() =>
  import('@/pages/setup/SetupStepIntro').then((m) => ({ default: m.SetupStepIntro }))
)
const SetupStepAdmin = lazy(() =>
  import('@/pages/setup/SetupStepAdmin').then((m) => ({ default: m.SetupStepAdmin }))
)
const SetupStepSecurity = lazy(() =>
  import('@/pages/setup/SetupStepSecurity').then((m) => ({ default: m.SetupStepSecurity }))
)
const SetupStepIntegrations = lazy(() =>
  import('@/pages/setup/SetupStepIntegrations').then((m) => ({ default: m.SetupStepIntegrations }))
)
const SetupStepComplete = lazy(() =>
  import('@/pages/setup/SetupStepComplete').then((m) => ({ default: m.SetupStepComplete }))
)
const SetupStepEnableBanking = lazy(() =>
  import('@/pages/setup/integrations/SetupStepEnableBanking').then((m) => ({
    default: m.SetupStepEnableBanking,
  }))
)
const SetupStepBoursoBank = lazy(() =>
  import('@/pages/setup/integrations/SetupStepBoursoBank').then((m) => ({
    default: m.SetupStepBoursoBank,
  }))
)
const SetupStepTradeRepublic = lazy(() =>
  import('@/pages/setup/integrations/SetupStepTradeRepublic').then((m) => ({
    default: m.SetupStepTradeRepublic,
  }))
)
const SetupStepFinary = lazy(() =>
  import('@/pages/setup/integrations/SetupStepFinary').then((m) => ({
    default: m.SetupStepFinary,
  }))
)
const SetupStepCrypto = lazy(() =>
  import('@/pages/setup/integrations/SetupStepCrypto').then((m) => ({
    default: m.SetupStepCrypto,
  }))
)

const NotFoundPage = lazy(() =>
  import('@/pages/error/NotFoundPage').then((m) => ({ default: m.NotFoundPage }))
)
const ForbiddenPage = lazy(() =>
  import('@/pages/error/ForbiddenPage').then((m) => ({ default: m.ForbiddenPage }))
)
const ServerErrorPage = lazy(() =>
  import('@/pages/error/ServerErrorPage').then((m) => ({ default: m.ServerErrorPage }))
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
    // /login/mfa is also for unauthenticated visitors — the user is mid-login
    // (mfa_challenge cookie set, access_token NOT yet set), so PublicOnly applies.
    path: '/login/mfa',
    element: (
      <PublicOnly>
        <SuspensePage>
          <MfaChallengePage />
        </SuspensePage>
      </PublicOnly>
    ),
  },
  {
    path: '/error/403',
    element: (
      <SuspensePage>
        <ForbiddenPage />
      </SuspensePage>
    ),
  },
  {
    path: '/error/500',
    element: (
      <SuspensePage>
        <ServerErrorPage />
      </SuspensePage>
    ),
  },
  {
    path: '/setup',
    element: (
      <SetupOnly>
        <SuspensePage>
          <SetupLayout />
        </SuspensePage>
      </SetupOnly>
    ),
    children: [
      { index: true, element: <SuspensePage><SetupStepIntro /></SuspensePage> },
      { path: 'admin', element: <SuspensePage><SetupStepAdmin /></SuspensePage> },
      { path: 'security', element: <SuspensePage><SetupStepSecurity /></SuspensePage> },
      { path: 'integrations', element: <SuspensePage><SetupStepIntegrations /></SuspensePage> },
      { path: 'integrations/enablebanking', element: <SuspensePage><SetupStepEnableBanking /></SuspensePage> },
      { path: 'integrations/boursobank', element: <SuspensePage><SetupStepBoursoBank /></SuspensePage> },
      { path: 'integrations/traderepublic', element: <SuspensePage><SetupStepTradeRepublic /></SuspensePage> },
      { path: 'integrations/finary', element: <SuspensePage><SetupStepFinary /></SuspensePage> },
      { path: 'integrations/crypto', element: <SuspensePage><SetupStepCrypto /></SuspensePage> },
      { path: 'done', element: <SuspensePage><SetupStepComplete /></SuspensePage> },
    ],
  },
  {
    path: '/',
    element: (
      <RequireSetup>
        <RequireAuth>
          <AppLayout />
        </RequireAuth>
      </RequireSetup>
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
      { path: 'family', element: <SuspensePage><FamilyDashboardPage /></SuspensePage> },
      { path: 'settings/family', element: <SuspensePage><FamilySettingsPage /></SuspensePage> },
      { path: 'admin', element: <SuspensePage><RequireAdmin><AdminPage /></RequireAdmin></SuspensePage> },
    ],
  },
  {
    path: '/activate/:token',
    element: (
      <SuspensePage>
        <ActivationPage />
      </SuspensePage>
    ),
  },
  {
    path: '*',
    element: (
      <SuspensePage>
        <NotFoundPage />
      </SuspensePage>
    ),
  },
])
