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
