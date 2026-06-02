import { useMutation } from '@tanstack/react-query'
import { exportApi, type ExportRequest } from './api'

export function useExportData() {
  return useMutation({
    mutationFn: async (req: ExportRequest) => {
      const { blob, filename } = await exportApi.download(req)
      // Trigger download via anchor — works without popup blockers because
      // it's a synchronous user-gesture-initiated click on an in-DOM element.
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      // Revoke after the click is processed; doing it immediately can race in Safari.
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      return { filename }
    },
  })
}
