import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { HoldingInsightSection } from './HoldingInsightSection'
import type { SecurityInsight } from '@/types/api'

const useSecurityInsight = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  useSecurityInsight: (...args: unknown[]) => useSecurityInsight(...args),
}))

// Return the interpolated key so assertions can match on stable identifiers.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

function mockInsight(data: SecurityInsight | Record<string, never> | undefined, isLoading = false) {
  useSecurityInsight.mockReturnValue({ data, isLoading })
}

const etfComposition: SecurityInsight = {
  ticker: 'IWDA',
  assetType: 'ETF',
  composition: {
    companies: [{ label: 'Apple', percent: 5.1 }, { label: 'Microsoft', percent: 4.4 }],
    countries: [{ label: 'United States', percent: 70.8 }, { label: 'Japan', percent: 6.0 }],
    sectors: [{ label: 'Information Technology', percent: 24.1 }],
    source: 'iShares',
    asOf: '2026-05-31',
  },
}

describe('HoldingInsightSection', () => {
  it('renders the three composition bars for an ETF', () => {
    mockInsight(etfComposition)
    const { container } = render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)

    // Three PartitionBar instances — one per breakdown (companies/countries/sectors).
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(3)
    expect(screen.getByText('holdings.insight.companies')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.countries')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.sectors')).toBeInTheDocument()
    expect(screen.getByText('Apple')).toBeInTheDocument()
    expect(screen.getByText('United States')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.assetTypes.ETF')).toBeInTheDocument()
  })

  it('adds an "others" remainder segment when slices sum below 100%', () => {
    mockInsight(etfComposition)
    render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)
    // companies sum to 9.5% → an "Autres" remainder is expected.
    expect(screen.getAllByText('holdings.insight.others').length).toBeGreaterThan(0)
  })

  it('shows only the type badge (no bars) for a stock', () => {
    mockInsight({ ticker: 'AAPL', assetType: 'STOCK', composition: null })
    const { container } = render(<HoldingInsightSection ticker="AAPL" name="Apple Inc." open />)

    expect(screen.getByText('holdings.insight.assetTypes.STOCK')).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(0)
    expect(screen.queryByText('holdings.insight.unavailable')).not.toBeInTheDocument()
  })

  it('shows an unavailable note for an ETF without composition', () => {
    mockInsight({ ticker: 'XYZ', assetType: 'ETF', composition: null })
    render(<HoldingInsightSection ticker="XYZ" name="Some ETF" open />)
    expect(screen.getByText('holdings.insight.unavailable')).toBeInTheDocument()
  })

  it('renders nothing when the response is empty (unknown ticker / demo fallback)', () => {
    mockInsight({})
    const { container } = render(<HoldingInsightSection ticker="ZZZ" name="Mystery" open />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a spinner while loading', () => {
    mockInsight(undefined, true)
    const { container } = render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
  })
})
