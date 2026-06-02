import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type DateFormat = 'locale' | 'iso'

interface AppState {
  sidebarCollapsed: boolean
  demoMode: boolean
  dateFormat: DateFormat
  toggleSidebar: () => void
  setDemoMode: (enabled: boolean) => void
  setDateFormat: (format: DateFormat) => void
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      demoMode: import.meta.env.VITE_DEMO_MODE === 'true',
      dateFormat: 'locale',
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setDemoMode: (enabled) => set({ demoMode: enabled }),
      setDateFormat: (format) => set({ dateFormat: format }),
    }),
    { name: 'picsou-app', partialize: (s) => ({ sidebarCollapsed: s.sidebarCollapsed, dateFormat: s.dateFormat }) }
  )
)
