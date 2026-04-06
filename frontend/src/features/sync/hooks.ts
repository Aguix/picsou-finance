import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  bankSyncApi,
  trApi,
  cryptoExchangeApi,
  cryptoWalletApi,
  finaryApi,
} from './api'
import type {
  ExchangeType,
  ChainType,
  FinaryAccountMapping,
  FinaryImportRequest,
} from '@/types/api'

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

export const syncKeys = {
  all: ['sync'] as const,
  banks: () => [...syncKeys.all, 'banks'] as const,
  institutions: (q: string) => [...syncKeys.all, 'institutions', q] as const,
  tr: () => [...syncKeys.all, 'tr'] as const,
  exchanges: () => [...syncKeys.all, 'exchanges'] as const,
  wallets: () => [...syncKeys.all, 'wallets'] as const,
  finary: () => [...syncKeys.all, 'finary'] as const,
}

// ---------------------------------------------------------------------------
// Bank Sync (Enable Banking)
// ---------------------------------------------------------------------------

export function useBankSyncStatus() {
  return useQuery({
    queryKey: syncKeys.banks(),
    queryFn: bankSyncApi.getStatus,
    staleTime: 30_000,
    refetchInterval: 30_000,
  })
}

export function useSearchInstitutions(query: string) {
  return useQuery({
    queryKey: syncKeys.institutions(query),
    queryFn: () => bankSyncApi.searchInstitutions(query),
    enabled: query.length >= 2,
  })
}

export function useInitiateBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ institutionId, institutionName }: { institutionId: string; institutionName: string }) =>
      bankSyncApi.initiate(institutionId, institutionName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useCompleteBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => bankSyncApi.complete(code),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useRetryBankSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => bankSyncApi.retry(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useDeleteBankConnection() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => bankSyncApi.deleteConnection(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.banks() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Trade Republic
// ---------------------------------------------------------------------------

export function useTrSessionStatus() {
  return useQuery({
    queryKey: syncKeys.tr(),
    queryFn: trApi.getSessionStatus,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useInitiateTrAuth() {
  return useMutation({
    mutationFn: ({ phoneNumber, pin }: { phoneNumber: string; pin: string }) =>
      trApi.initiateAuth(phoneNumber, pin),
  })
}

export function useCompleteTrAuth() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ processId, tan }: { processId: string; tan: string }) =>
      trApi.completeAuth(processId, tan),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useSyncTradeRepublic() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => trApi.sync(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useImportTrCsv() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => trApi.importCsv(file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useClearTrSession() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => trApi.clearSession(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
    },
  })
}

// ---------------------------------------------------------------------------
// Crypto Exchanges
// ---------------------------------------------------------------------------

export function useCryptoExchangeStatuses() {
  return useQuery({
    queryKey: syncKeys.exchanges(),
    queryFn: cryptoExchangeApi.getStatuses,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useAddCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ type, apiKey, apiSecret }: { type: ExchangeType; apiKey: string; apiSecret: string }) =>
      cryptoExchangeApi.add(type, apiKey, apiSecret),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useSyncCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.sync(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useRemoveCryptoExchange() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoExchangeApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.exchanges() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Crypto Wallets
// ---------------------------------------------------------------------------

export function useCryptoWallets() {
  return useQuery({
    queryKey: syncKeys.wallets(),
    queryFn: cryptoWalletApi.list,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })
}

export function useAddCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ chain, address, label }: { chain: ChainType; address: string; label?: string }) =>
      cryptoWalletApi.add(chain, address, label),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useSyncCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoWalletApi.sync(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useRemoveCryptoWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => cryptoWalletApi.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: syncKeys.wallets() })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

// ---------------------------------------------------------------------------
// Finary
// ---------------------------------------------------------------------------

export function useIsFinaryConfigured() {
  return useQuery({
    queryKey: syncKeys.finary(),
    queryFn: finaryApi.isConfigured,
    staleTime: 5 * 60_000,
  })
}

export function usePreviewFinaryFile() {
  return useMutation({
    mutationFn: (file: File) => finaryApi.previewFile(file),
  })
}

export function usePreviewFinaryApi() {
  return useMutation({
    mutationFn: (totp?: string) => finaryApi.previewApi(totp),
  })
}

export function useImportFinary() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: FinaryImportRequest) => finaryApi.import(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useExecuteFinaryApiSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ syncToken, mappings }: { syncToken: string; mappings: FinaryAccountMapping[] }) =>
      finaryApi.executeApiSync(syncToken, mappings),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}
