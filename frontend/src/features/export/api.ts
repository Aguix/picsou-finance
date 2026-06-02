import { api } from '@/lib/api-client'

export interface ExportRequest {
  reAuth: {
    password?: string | null
    totpCode?: string | null
  }
  includeBalanceSnapshots: boolean
}

/**
 * Streams the GDPR data export as a ZIP. Returns a Blob so the caller can
 * trigger a browser download via an anchor click. The Axios `responseType:
 * 'blob'` is critical — without it Axios tries to parse the binary body as
 * UTF-8 text and corrupts the ZIP central directory.
 */
export const exportApi = {
  download: async (req: ExportRequest): Promise<{ blob: Blob; filename: string }> => {
    const res = await api.post<Blob>('/me/export', req, { responseType: 'blob' })
    const cd = res.headers['content-disposition'] as string | undefined
    const match = cd?.match(/filename="([^"]+)"/)
    const filename = match?.[1] ?? 'picsou-export.zip'
    return { blob: res.data, filename }
  },
}
