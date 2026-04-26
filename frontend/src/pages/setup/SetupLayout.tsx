import { Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useEffect, useMemo } from 'react'
import { Progress } from '@/components/ui/progress'
import { cn } from '@/lib/utils'

/**
 * Shell for every setup route. Intentionally minimal — Apple's setup
 * assistants let the content breathe: one top progress strip, one
 * "Step N of M" pill, and everything else is negative space.
 *
 * The step counter comes from URL path, not a nav state, so a browser
 * refresh keeps the right index visible.
 */

const MAIN_STEPS: { path: string; i18nKey: string }[] = [
  { path: '/setup', i18nKey: 'setup.steps.hello' },
  { path: '/setup/admin', i18nKey: 'setup.steps.admin' },
  { path: '/setup/security', i18nKey: 'setup.steps.security' },
  { path: '/setup/integrations', i18nKey: 'setup.steps.integrations' },
  { path: '/setup/done', i18nKey: 'setup.steps.done' },
]

export function SetupLayout() {
  const { t } = useTranslation()
  const location = useLocation()

  const { index, total, label } = useMemo(() => {
    // Pick the longest-prefix match so sub-routes like /setup/integrations/enablebanking
    // resolve to the Integrations step, not Hello.
    const sorted = [...MAIN_STEPS].sort((a, b) => b.path.length - a.path.length)
    const match = sorted.find(s => location.pathname.startsWith(s.path))
      ?? MAIN_STEPS[0]
    const idx = MAIN_STEPS.findIndex(s => s.path === match.path)
    return { index: idx + 1, total: MAIN_STEPS.length, label: t(match.i18nKey) }
  }, [location.pathname, t])

  useEffect(() => {
    // Preload the Homemade Apple font only on setup routes — dashboard
    // users pay zero font-cost for the wizard's typography.
    const id = 'picsou-setup-font-preload'
    if (document.getElementById(id)) return
    const link = document.createElement('link')
    link.id = id
    link.rel = 'preload'
    link.as = 'font'
    link.type = 'font/woff2'
    link.crossOrigin = 'anonymous'
    link.href = '/fonts/HomemadeApple-Regular.woff2'
    document.head.appendChild(link)
  }, [])

  const progressPct = Math.round((index / total) * 100)

  return (
    <div className="relative min-h-dvh bg-background setup-gradient">
      <Progress
        value={progressPct}
        aria-label={t('setup.progress.bar', { current: index, total })}
        className="fixed inset-x-0 top-0 z-50 h-1 rounded-none"
      />

      <header className="fixed right-4 top-4 z-40 sm:right-6 sm:top-6">
        <span
          className={cn(
            'inline-flex items-center rounded-full border border-border/60',
            'bg-background/80 px-3 py-1 text-xs font-medium text-muted-foreground',
            'backdrop-blur-md shadow-sm'
          )}
          aria-live="polite"
        >
          {t('setup.progress.label', { current: index, total })} — {label}
        </span>
      </header>

      <main
        id="setup-main"
        className="mx-auto flex min-h-dvh w-full max-w-xl flex-col justify-center px-5 py-20 sm:max-w-2xl sm:px-8 sm:py-24"
      >
        <a
          href="#setup-main"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-foreground"
        >
          {t('setup.a11y.skipToContent')}
        </a>

        <div
          key={location.pathname}
          className="w-full animate-setup-slide-in"
        >
          <Outlet />
        </div>
      </main>
    </div>
  )
}
