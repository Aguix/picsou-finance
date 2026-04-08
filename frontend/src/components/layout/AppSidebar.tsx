import React from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  LayoutDashboard,
  Wallet,
  Target,
  RefreshCw,
  Settings,
  LogOut,
  Languages,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarSeparator,
} from '@/components/ui/sidebar'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { useLogout } from '@/features/auth/hooks'

function NavButton({
  to,
  end,
  icon,
  tooltip,
  label,
}: {
  to: string
  end?: boolean
  icon: LucideIcon
  tooltip: string
  label: string
}) {
  const location = useLocation()
  const isActive = end
    ? location.pathname === to
    : location.pathname.startsWith(to)

  return (
    <SidebarMenuButton asChild tooltip={tooltip} isActive={isActive}>
      <NavLink to={to} end={end}>
        {React.createElement(icon)}
        <span>{label}</span>
      </NavLink>
    </SidebarMenuButton>
  )
}

const NAV_ITEMS = [
  { path: '/', icon: LayoutDashboard, labelKey: 'nav.dashboard' },
  { path: '/accounts', icon: Wallet, labelKey: 'nav.accounts' },
  { path: '/goals', icon: Target, labelKey: 'nav.goals' },
  { path: '/sync', icon: RefreshCw, labelKey: 'nav.sync' },
  { path: '/settings', icon: Settings, labelKey: 'nav.settings' },
] as const

export function AppSidebar() {
  const { t, i18n } = useTranslation()
  const username = useAuthStore((s) => s.username)
  const demoMode = useAppStore((s) => s.demoMode)
  const logoutMutation = useLogout()

  const displayName = demoMode ? 'Demo' : username ?? ''
  const initial = displayName.charAt(0).toUpperCase()

  function toggleLanguage() {
    i18n.changeLanguage(i18n.language === 'fr' ? 'en' : 'fr')
  }

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="overflow-hidden">
        <div className="flex items-center gap-2">
          <span className="text-lg font-bold tracking-tight group-data-[collapsible=icon]:hidden">
            Picsou
          </span>
          {demoMode && (
            <Badge
              variant="secondary"
              className="text-[10px] px-1.5 py-0 group-data-[collapsible=icon]:hidden"
            >
              DEMO
            </Badge>
          )}
        </div>
      </SidebarHeader>

      <SidebarSeparator className="data-horizontal:w-auto" />

      <SidebarContent>
        <SidebarMenu>
          {NAV_ITEMS.map((item) => (
            <SidebarMenuItem key={item.path}>
              <NavButton
                to={item.path}
                end={item.path === '/'}
                icon={item.icon}
                tooltip={t(item.labelKey)}
                label={t(item.labelKey)}
              />
            </SidebarMenuItem>
          ))}
        </SidebarMenu>
      </SidebarContent>

      <SidebarSeparator className="data-horizontal:w-auto" />

      <SidebarFooter>
        <div className="flex items-center gap-2 py-1">
          <Avatar size="sm">
            <AvatarFallback className="text-xs font-medium">
              {initial}
            </AvatarFallback>
          </Avatar>
          <span className="truncate text-sm font-medium group-data-[collapsible=icon]:hidden">
            {displayName}
          </span>
          {demoMode && (
            <Badge
              variant="outline"
              className="ml-auto text-[10px] px-1.5 py-0 group-data-[collapsible=icon]:hidden"
            >
              DEMO
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-1 group-data-[collapsible=icon]:flex-col">
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={toggleLanguage}
            className="shrink-0"
            aria-label={t('common.language')}
          >
            <Languages />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => logoutMutation.mutate()}
            disabled={logoutMutation.isPending}
            className="shrink-0"
            aria-label={t('auth.logout')}
          >
            <LogOut />
          </Button>
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}
