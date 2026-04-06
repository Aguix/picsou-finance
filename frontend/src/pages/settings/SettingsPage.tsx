import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import {
  PaintBrush01Icon,
  Globe02Icon,
  UserIcon,
  InformationCircleIcon,
  Logout03Icon,
} from '@hugeicons/core-free-icons'
import { HugeiconsIcon } from '@hugeicons/react'
import type { IconSvgElement } from '@hugeicons/react'

// ---------------------------------------------------------------------------
// Toggle group button (theme / language)
// ---------------------------------------------------------------------------

interface ToggleOption {
  value: string
  label: string
}

function ToggleGroup({
  options,
  value,
  onChange,
}: {
  options: ToggleOption[]
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="inline-flex items-center rounded-lg bg-muted p-1">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
            value === opt.value
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Settings section card wrapper
// ---------------------------------------------------------------------------

function SectionCard({
  icon,
  title,
  description,
  children,
}: {
  icon: IconSvgElement
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <Card className="rounded-4xl bg-card shadow-md">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <HugeiconsIcon icon={icon} className="size-5 text-muted-foreground" />
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Theme helpers
// ---------------------------------------------------------------------------

type Theme = 'light' | 'dark' | 'system'

function getStoredTheme(): Theme {
  return (localStorage.getItem('theme') as Theme) ?? 'system'
}

function applyTheme(theme: Theme) {
  const root = document.documentElement
  if (theme === 'dark') {
    root.classList.add('dark')
  } else if (theme === 'light') {
    root.classList.remove('dark')
  } else {
    // system
    if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
      root.classList.add('dark')
    } else {
      root.classList.remove('dark')
    }
  }
  localStorage.setItem('theme', theme)
}

// ---------------------------------------------------------------------------
// SettingsPage
// ---------------------------------------------------------------------------

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { demoMode, setDemoMode } = useAppStore()
  const { username, logout } = useAuthStore()

  // Theme -----------------------------------------------------------------
  const [theme, setTheme] = useState<Theme>(getStoredTheme)

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  // Listen for system preference changes when theme is 'system'
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => {
      if (getStoredTheme() === 'system') applyTheme('system')
    }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  // Language --------------------------------------------------------------
  const [locale, setLocale] = useState(i18n.language)

  const handleLocaleChange = (lng: string) => {
    i18n.changeLanguage(lng)
    localStorage.setItem('locale', lng)
    setLocale(lng)
  }

  // Logout ----------------------------------------------------------------
  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  // Theme / locale options
  const themeOptions: ToggleOption[] = [
    { value: 'light', label: t('settings.themeLight') },
    { value: 'dark', label: t('settings.themeDark') },
    { value: 'system', label: t('settings.themeSystem') },
  ]

  const localeOptions: ToggleOption[] = [
    { value: 'fr', label: 'FR' },
    { value: 'en', label: 'EN' },
  ]

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <PageHeader title={t('settings.title')} />

      {/* Appearance ------------------------------------------------------- */}
      <SectionCard
        icon={PaintBrush01Icon}
        title={t('settings.appearance')}
        description={t('settings.appearanceDescription')}
      >
        <div className="space-y-6">
          {/* Theme */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.theme')}</Label>
            <ToggleGroup
              options={themeOptions}
              value={theme}
              onChange={(v) => setTheme(v as Theme)}
            />
          </div>

          {/* Language */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.language')}</Label>
            <ToggleGroup
              options={localeOptions}
              value={locale.startsWith('fr') ? 'fr' : 'en'}
              onChange={handleLocaleChange}
            />
          </div>
        </div>
      </SectionCard>

      {/* Demo Mode -------------------------------------------------------- */}
      <SectionCard
        icon={InformationCircleIcon}
        title={t('settings.demoMode')}
        description={t('settings.demoModeDesc')}
      >
        <div className="flex items-center justify-between">
          <Label htmlFor="demo-toggle" className="text-sm font-medium">
            {t('settings.demoMode')}
          </Label>
          <Switch
            id="demo-toggle"
            checked={demoMode}
            onCheckedChange={setDemoMode}
          />
        </div>
      </SectionCard>

      {/* Account ---------------------------------------------------------- */}
      <SectionCard
        icon={UserIcon}
        title={t('settings.account')}
        description={t('settings.accountDescription')}
      >
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">
              {t('settings.username')}
            </Label>
            <Input
              value={username ?? ''}
              readOnly
              className="max-w-[200px] bg-muted"
            />
          </div>
          <div className="flex justify-end">
            <Button variant="destructive" onClick={handleLogout}>
              <HugeiconsIcon icon={Logout03Icon} className="mr-2 size-4" />
              {t('settings.logout')}
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* About ------------------------------------------------------------ */}
      <SectionCard
        icon={Globe02Icon}
        title={t('settings.about')}
        description={t('settings.aboutDescription')}
      >
        <div className="space-y-3 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">Picsou</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">
              {t('settings.version')}
            </span>
            <span className="font-medium">1.0.0</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">GitHub</span>
            <span className="font-medium">github.com/zoeille/picsou</span>
          </div>
        </div>
      </SectionCard>
    </div>
  )
}
