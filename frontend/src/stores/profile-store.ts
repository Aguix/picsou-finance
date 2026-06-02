import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface ProfileState {
  activeMemberId: number | null
  viewMode: 'own' | 'managed' | 'family'
  setActiveMember: (memberId: number | null) => void
  setViewMode: (mode: 'own' | 'managed' | 'family') => void
  reset: () => void
}

export const useProfileStore = create<ProfileState>()(
  persist(
    (set) => ({
      activeMemberId: null,
      viewMode: 'own',
      setActiveMember: (memberId) => set({ activeMemberId: memberId, viewMode: memberId ? 'managed' : 'own' }),
      setViewMode: (mode) => set({ viewMode: mode, activeMemberId: mode === 'family' ? null : undefined as unknown as null }),
      reset: () => set({ activeMemberId: null, viewMode: 'own' }),
    }),
    { name: 'picsou-profile' }
  )
)
