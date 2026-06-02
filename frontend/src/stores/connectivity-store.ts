import { create } from 'zustand'

interface ConnectivityState {
  isConnected: boolean
  isChecking: boolean
  setConnected: (connected: boolean) => void
  setChecking: (checking: boolean) => void
}

export const useConnectivityStore = create<ConnectivityState>((set) => ({
  isConnected: true,
  isChecking: false,
  setConnected: (connected) => set({ isConnected: connected }),
  setChecking: (checking) => set({ isChecking: checking }),
}))
