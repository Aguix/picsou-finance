import { useQuery } from '@tanstack/react-query'
import { dashboardApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

export function useDashboard(range?: string) {
  return useQuery({
    queryKey: ['dashboard', range],
    queryFn: () => dashboardApi.get(range),
    staleTime: QUERY_STALE_TIMES.dashboard,
  })
}

export function useNetWorthIntraday(accountIds: number[], enabled: boolean) {
  return useQuery({
    queryKey: ['net-worth-intraday', accountIds],
    queryFn: () => dashboardApi.getIntraday(accountIds),
    enabled: enabled && accountIds.length > 0,
    staleTime: 2 * 60 * 1000, // 2 minutes
  })
}

export function usePnl(accountIds: number[], from?: string) {
  return useQuery({
    queryKey: ['pnl', accountIds, from],
    queryFn: () => dashboardApi.getPnl(accountIds, from),
    enabled: accountIds.length > 0,
    staleTime: QUERY_STALE_TIMES.dashboard,
  })
}
