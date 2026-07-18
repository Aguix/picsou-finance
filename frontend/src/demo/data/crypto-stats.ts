import type { CryptoStatsResponse } from '@/types/api'

/**
 * Demo consolidated crypto stats, mirroring the three coins held on the demo Crypto account (id=6):
 * BTC and ETH carry a few buys + reward income (so the cost-vs-price overlay, accumulation curve,
 * rewards-by-program bars, per-token donut and reward yield all render), SOL is a plain position.
 */
export const mockCryptoStats: CryptoStatsResponse = {
  assets: [
    {
      ticker: 'BTC',
      name: 'Bitcoin',
      quantity: 0.032,
      averageBuyIn: 52000,
      currentPrice: 84500,
      currentValueEur: 2704,
      costBasisEur: 1664,
      totalInvestedEur: 1664,
      totalRewardsEur: 40,
      totalRewardsQty: 0.0005,
      rewardsYieldPct: 2.4,
      unrealizedPnlEur: 1040,
      firstBuyDate: '2023-06-15',
      lastActivityDate: '2024-11-20',
      rewardsByKindEur: { EARN: 25, STAKING: 15 },
      buyEvents: [
        { date: '2023-06-15', quantity: 0.02, pricePerUnit: 48000, valueEur: 960 },
        { date: '2024-02-10', quantity: 0.012, pricePerUnit: 58666, valueEur: 704 },
      ],
      sellEvents: [],
      rewardEvents: [
        { date: '2024-01-05', kind: 'EARN', quantity: 0.0003, valueEur: 25 },
        { date: '2024-11-20', kind: 'STAKING', quantity: 0.0002, valueEur: 15 },
      ],
      costSeries: [
        { date: '2023-06-15', averageBuyIn: 48000 },
        { date: '2024-02-10', averageBuyIn: 52000 },
      ],
      priceSeries: [
        { date: '2023-06-15', priceEur: 48000 },
        { date: '2024-02-10', priceEur: 58000 },
        { date: '2024-11-20', priceEur: 84500 },
      ],
    },
    {
      ticker: 'ETH',
      name: 'Ethereum',
      quantity: 1.2,
      averageBuyIn: 1800,
      currentPrice: 2100,
      currentValueEur: 2520,
      costBasisEur: 2160,
      totalInvestedEur: 2160,
      totalRewardsEur: 12,
      totalRewardsQty: 0.006,
      rewardsYieldPct: 0.56,
      unrealizedPnlEur: 360,
      firstBuyDate: '2023-09-01',
      lastActivityDate: '2024-08-01',
      rewardsByKindEur: { STAKING: 12 },
      buyEvents: [
        { date: '2023-09-01', quantity: 1.2, pricePerUnit: 1800, valueEur: 2160 },
      ],
      sellEvents: [],
      rewardEvents: [
        { date: '2024-08-01', kind: 'STAKING', quantity: 0.006, valueEur: 12 },
      ],
      costSeries: [
        { date: '2023-09-01', averageBuyIn: 1800 },
      ],
      priceSeries: [
        { date: '2023-09-01', priceEur: 1800 },
        { date: '2024-08-01', priceEur: 2100 },
      ],
    },
    {
      ticker: 'SOL',
      name: 'Solana',
      quantity: 15,
      averageBuyIn: 95,
      currentPrice: 148,
      currentValueEur: 2220,
      costBasisEur: 1425,
      totalInvestedEur: 1425,
      totalRewardsEur: 0,
      totalRewardsQty: 0,
      rewardsYieldPct: null,
      unrealizedPnlEur: 795,
      firstBuyDate: '2024-03-12',
      lastActivityDate: '2024-03-12',
      rewardsByKindEur: {},
      buyEvents: [
        { date: '2024-03-12', quantity: 15, pricePerUnit: 95, valueEur: 1425 },
      ],
      sellEvents: [],
      rewardEvents: [],
      costSeries: [
        { date: '2024-03-12', averageBuyIn: 95 },
      ],
      priceSeries: [
        { date: '2024-03-12', priceEur: 95 },
      ],
    },
  ],
  totals: {
    totalInvestedEur: 5249,
    totalRewardsEur: 52,
    currentValueEur: 7444,
    rewardsYieldPct: 0.99,
    rewardsByKindEur: { EARN: 25, STAKING: 27 },
  },
}
