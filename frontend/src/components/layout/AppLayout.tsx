import { Outlet } from 'react-router-dom'
import { AppSidebar } from './AppSidebar'
import { MobileBottomNav } from './MobileBottomNav'
import { DegradedModeBanner } from '@/components/shared/DegradedModeBanner'

export function AppLayout() {
  return (
    <div className="flex h-screen md:p-4 md:gap-4">
      <AppSidebar />
      <main className="flex-1 overflow-auto flex flex-col pb-20 md:pb-0">
        <DegradedModeBanner />
        <div className="flex-1 overflow-auto">
          <Outlet />
        </div>
      </main>
      <MobileBottomNav />
    </div>
  )
}
