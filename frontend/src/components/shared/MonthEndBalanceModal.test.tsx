import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MonthEndBalanceModal } from './MonthEndBalanceModal'

const mutateAsync = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  useAddSnapshot: () => ({ mutateAsync }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string, fallback?: string) => fallback ?? key }),
}))

function renderModal(onClose = vi.fn()) {
  render(<MonthEndBalanceModal open onClose={onClose} accountId={1} history={[]} />)
  return { onClose }
}

function editFirstMonth(value = '1234') {
  const input = screen.getAllByRole('spinbutton')[0]
  fireEvent.change(input, { target: { value } })
  return input
}

function saveButton() {
  return screen.getByRole('button', { name: /addSnapshot/i })
}

describe('MonthEndBalanceModal', () => {
  beforeEach(() => {
    mutateAsync.mockReset()
  })

  it('shows the server error message in the UI when a save fails', async () => {
    mutateAsync.mockRejectedValue({
      response: { data: { detail: "Method 'POST' is not supported." } },
    })
    const { onClose } = renderModal()

    editFirstMonth()
    fireEvent.click(saveButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Method 'POST' is not supported.")
    // failure keeps the modal open and re-enables the save button (spinner cleared)
    expect(onClose).not.toHaveBeenCalled()
    await waitFor(() => expect(saveButton()).not.toBeDisabled())
  })

  it('closes the modal after a successful save', async () => {
    mutateAsync.mockResolvedValue({})
    const { onClose } = renderModal()

    editFirstMonth()
    fireEvent.click(saveButton())

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
