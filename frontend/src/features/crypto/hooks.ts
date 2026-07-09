import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cryptoApi } from './api'
import type { CryptoImportRequest } from '@/types/api'

export const cryptoKeys = {
  all: ['crypto'] as const,
  sources: () => [...cryptoKeys.all, 'sources'] as const,
}

/** The supported CSV source formats — static per backend build, cached aggressively. */
export function useCryptoSources() {
  return useQuery({
    queryKey: cryptoKeys.sources(),
    queryFn: () => cryptoApi.sources(),
    staleTime: Infinity,
  })
}

export function usePreviewCryptoCsv() {
  return useMutation({
    mutationFn: (file: File) => cryptoApi.preview(file),
  })
}

export function useImportCrypto() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CryptoImportRequest) => cryptoApi.import(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}
