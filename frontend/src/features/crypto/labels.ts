import type { RewardKind } from '@/types/api'

/** Display labels for reward programs (FR — the app's primary locale). */
export const REWARD_KIND_LABELS: Record<RewardKind, string> = {
  EARN: 'Earn',
  STAKING: 'Staking',
  SUPERCHARGER: 'Supercharger',
  AIRDROP: 'Airdrop Arena',
  CASHBACK: 'Cashback carte',
  REFERRAL: 'Parrainage',
  CAMPAIGN: 'Campagnes',
  DEFI_YIELD: 'DeFi Yield',
  OTHER: 'Autres',
}

/** Stable chart colour per reward program, reusing the theme's chart palette. */
export const REWARD_KIND_COLORS: Record<RewardKind, string> = {
  EARN: 'var(--chart-1)',
  STAKING: 'var(--chart-2)',
  SUPERCHARGER: 'var(--chart-3)',
  AIRDROP: 'var(--chart-4)',
  CASHBACK: 'var(--chart-5)',
  REFERRAL: 'var(--chart-1)',
  CAMPAIGN: 'var(--chart-3)',
  DEFI_YIELD: 'var(--chart-4)',
  OTHER: 'var(--chart-2)',
}
