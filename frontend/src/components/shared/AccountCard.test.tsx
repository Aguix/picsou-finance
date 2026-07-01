import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { AccountCard } from './AccountCard'
import type { Account } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const baseAccount: Account = {
  id: 1,
  name: 'Compte Courant BNP',
  type: 'CHECKING',
  provider: 'BNP Paribas',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: null,
  isManual: false,
  color: '#6366f1',
  ticker: null,
  logoUrl: null,
  createdAt: '2024-01-01T00:00:00Z',
}

describe('AccountCard', () => {
  it('renders a colored circle when the account has no logo', () => {
    const { container } = render(<AccountCard account={baseAccount} />)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    const dot = container.querySelector('[style*="background-color"]')
    expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
  })

  it('renders the bank logo image when logoUrl is set', () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/bnp.png' }
    const { container } = render(<AccountCard account={account} />)
    const img = container.querySelector('img') as HTMLImageElement
    expect(img).toHaveAttribute('src', 'https://logos.example/bnp.png')
  })

  it('falls back to the colored circle if the logo image fails to load', () => {
    const account = { ...baseAccount, logoUrl: 'https://logos.example/broken.png' }
    const { container } = render(<AccountCard account={account} />)
    const img = container.querySelector('img') as HTMLImageElement

    fireEvent.error(img)

    expect(container.querySelector('img')).not.toBeInTheDocument()
    const dot = container.querySelector('[style*="background-color"]')
    expect(dot).toHaveStyle({ backgroundColor: '#6366f1' })
  })
})
