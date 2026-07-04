import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CryptoStatsSection } from './CryptoStatsSection'
import type { CryptoStatsResponse } from '@/types/api'

const useCryptoStats = vi.fn()

vi.mock('@/features/crypto/hooks', () => ({
  useCryptoStats: (...args: unknown[]) => useCryptoStats(...args),
}))

// i18n stub: map the few keys that must resolve to real values (currency/locale,
// used by CurrencyDisplay → Intl.NumberFormat), and fall back to the provided
// default string for everything else (mirrors t(key, 'fallback')).
const TEST_LABELS: Record<string, string> = {
  'common.currency': 'EUR',
  'common.locale': 'fr-FR',
}
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: unknown) =>
      TEST_LABELS[key] ?? (typeof fallback === 'string' ? fallback : key),
  }),
}))

function mockStats(data: CryptoStatsResponse | undefined, isLoading = false) {
  useCryptoStats.mockReturnValue({ data, isLoading })
}

// Mirrors the shape returned live by GET /api/crypto/accounts/{id}/stats.
const stats: CryptoStatsResponse = {
  assets: [
    {
      ticker: 'BTC',
      name: 'BTC',
      logoUrl: null,
      quantity: 0.0131,
      averageBuyIn: 30463.58,
      currentPrice: 55005,
      currentValueEur: 720.57,
      costBasisEur: 399.07,
      totalInvestedEur: 460,
      totalRewardsEur: 3,
      totalRewardsQty: 0.0001,
      unrealizedPnlEur: 321.49,
      firstBuyDate: '2024-01-10',
      lastActivityDate: '2024-05-12',
      rewardsByKindEur: { EARN: 3 },
      buyEvents: [
        { date: '2024-01-10', quantity: 0.01, pricePerUnit: 30000, valueEur: 300 },
        { date: '2024-02-05', quantity: 0.005, pricePerUnit: 32000, valueEur: 160 },
      ],
      rewardEvents: [{ date: '2024-03-08', kind: 'EARN', quantity: 0.0001, valueEur: 3 }],
      sellEvents: [{ date: '2024-05-12', quantity: 0.002, pricePerUnit: 35000, valueEur: 70 }],
      costSeries: [
        { date: '2024-01-10', averageBuyIn: 30000 },
        { date: '2024-02-05', averageBuyIn: 30666.67 },
        { date: '2024-03-08', averageBuyIn: 30463.58 },
      ],
      priceSeries: [
        { date: '2024-01-10', priceEur: 38000 },
        { date: '2024-02-05', priceEur: 41000 },
        { date: '2024-05-12', priceEur: 55005 },
      ],
    },
    {
      ticker: 'CRO',
      name: 'CRO',
      logoUrl: null,
      quantity: 62.5,
      averageBuyIn: 0,
      currentPrice: null,
      currentValueEur: null,
      costBasisEur: 0,
      totalInvestedEur: 0,
      totalRewardsEur: 6.25,
      totalRewardsQty: 62.5,
      unrealizedPnlEur: null,
      firstBuyDate: '2024-03-01',
      lastActivityDate: '2024-04-02',
      rewardsByKindEur: { EARN: 1.25, STAKING: 0.8, SUPERCHARGER: 4, CASHBACK: 0.2 },
      buyEvents: [],
      sellEvents: [],
      rewardEvents: [
        { date: '2024-03-01', kind: 'EARN', quantity: 12.5, valueEur: 1.25 },
        { date: '2024-03-15', kind: 'STAKING', quantity: 8, valueEur: 0.8 },
        { date: '2024-03-20', kind: 'SUPERCHARGER', quantity: 40, valueEur: 4 },
        { date: '2024-04-02', kind: 'CASHBACK', quantity: 2, valueEur: 0.2 },
      ],
      costSeries: [
        { date: '2024-03-01', averageBuyIn: 0 },
        { date: '2024-04-02', averageBuyIn: 0 },
      ],
      priceSeries: [],
    },
  ],
  totals: {
    totalInvestedEur: 1220,
    totalRewardsEur: 24.5,
    currentValueEur: 1118.04,
    rewardsByKindEur: { EARN: 7.5, STAKING: 0.8, SUPERCHARGER: 4, AIRDROP: 12, CASHBACK: 0.2 },
  },
}

describe('CryptoStatsSection', () => {
  it('renders totals, a card per asset and per-program reward badges', () => {
    mockStats(stats)
    render(<CryptoStatsSection accountId={1} />)

    // Title + one card per crypto.
    expect(screen.getByText('Statistiques crypto')).toBeInTheDocument()
    expect(screen.getByText('BTC')).toBeInTheDocument()
    expect(screen.getByText('CRO')).toBeInTheDocument()

    // Reward program labels surface (totals chart + per-asset badges).
    expect(screen.getAllByText('Earn').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Supercharger').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Airdrop Arena').length).toBeGreaterThan(0)

    // Reward-only CRO shows no avg buy-in / current price (—) but keeps its rewards.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)

    // The cost-vs-price overlay renders for assets that have a cost/price timeline.
    expect(screen.getAllByText('Coût moyen vs prix du marché').length).toBeGreaterThan(0)
  })

  it('renders a skeleton while loading', () => {
    mockStats(undefined, true)
    const { container } = render(<CryptoStatsSection accountId={1} />)
    expect(container.querySelector('[data-slot="skeleton"]')).toBeInTheDocument()
  })

  it('renders nothing when there are no assets', () => {
    mockStats({ assets: [], totals: stats.totals })
    const { container } = render(<CryptoStatsSection accountId={1} />)
    expect(container).toBeEmptyDOMElement()
  })
})
