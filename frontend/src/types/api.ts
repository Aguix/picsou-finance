export type AccountType =
  | 'LEP' | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS'
  | 'REAL_ESTATE' | 'LOAN' | 'OTHER'

export interface RealEstateMetadata {
  purchasePrice: number
  purchaseDate: string | null
  surfaceArea: number | null
  address: string | null
  propertyType: string | null
  rentalIncome: number | null
}

export interface DebtInfo {
  linkedAccountId: number | null
  linkedAccountName: string | null
  borrowedAmount: number
  interestRate: number | null
  monthlyPayment: number | null
  lenderName: string | null
  startDate: string | null
  endDate: string | null
  insuranceMonthly: number | null
  fileFees: number | null
}

export interface Account {
  id: number
  name: string
  type: AccountType
  provider: string | null
  currency: string
  currentBalance: number
  currentBalanceEur: number
  lastSyncedAt: string | null
  isManual: boolean
  color: string
  ticker: string | null
  logoUrl: string | null
  createdAt: string
  realEstate?: RealEstateMetadata
  debt?: DebtInfo
}

export interface AccountRequest {
  name: string
  type: AccountType
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color?: string
  ticker?: string
}

export interface RealEstateMetadataRequest {
  purchasePrice: number
  purchaseDate?: string
  surfaceArea?: number
  address?: string
  propertyType?: string
  rentalIncome?: number
}

export interface DebtRequest {
  linkedAccountId?: number | null
  borrowedAmount: number
  interestRate?: number
  monthlyPayment?: number
  lenderName?: string
  startDate?: string
  endDate?: string
  insuranceMonthly?: number
  fileFees?: number
}

export interface LoanInstallment {
  number: number
  date: string
  capital: number
  interest: number
  insurance: number
  totalPayment: number
  remainingBalance: number
}

export interface LoanSummary {
  totalInstallments: number
  paidInstallments: number
  remainingInstallments: number
  endDate: string | null
  monthlyPayment: number
  monthlyCapital: number
  monthlyInterest: number
  monthlyInsurance: number
  totalCost: number
  totalCapitalCost: number
  totalInterestCost: number
  totalInsuranceCost: number
  fileFees: number
  totalRepaid: number
  capitalRepaid: number
  interestRepaid: number
  insuranceRepaid: number
  remainingBalance: number
  capitalRepaidPct: number
}

export interface LoanScheduleResponse {
  summary: LoanSummary
  schedule: LoanInstallment[]
}

export interface BalanceSnapshot {
  id: number
  date: string
  balance: number
  investedAmount?: number
  createdAt?: string
}

export interface GoalProgress {
  id: number
  name: string
  targetAmount: number
  deadline: string
  createdAt: string
  historyStartMonth: string | null
  accounts: Account[]
  currentTotal: number
  percentComplete: number
  monthsLeft: number
  monthlyNeeded: number
  avgMonthlyContribution: number | null
  isOnTrack: boolean
  surplus: number
}

export interface GoalRequest {
  name: string
  targetAmount: number
  deadline: string
  accountIds: number[]
}

export interface GoalMonthEntry {
  yearMonth: string
  objective: number
  actual: number | null
  manualActual: number | null
  override: number | null
  effective: number | null
}

export interface DashboardData {
  totalNetWorth: number
  totalLiabilities: number
  netWorthHistory: { date: string; total: number; invested: number; pnl: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
  }[]
  liabilities: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
  }[]
  goalSummaries: GoalProgress[]
}

export interface Institution {
  id: string
  name: string
  bic: string | null
  logoUrl: string | null
  country: string
}

export interface HoldingResponse {
  ticker: string
  name: string | null
  quantity: number
  averageBuyIn: number | null
  currentPrice: number | null
  currentValueEur: number | null
  costBasisEur: number | null
  pnlEur: number | null
  pnlPercent: number | null
  priceUpdatedAt: string | null
  /** Registry AssetType of the underlying asset (CRYPTO, STOCK, ETF, UNKNOWN). */
  assetType: string | null
  /** Registry resolution status — drives the aggregator-link badge/editor. */
  assetStatus: AssetStatus | null
  // One field per aggregator ref, mirroring the registry: a non-null one means that aggregator can
  // quote this asset — so the holding-detail editor can show which aggregators are linked (and
  // whether there's a price fallback), not just CoinGecko.
  coingeckoId: string | null
  coinmarketcapId: string | null
  yahooSymbol: string | null
}

// --- Security insight (asset type + ETF composition) ---
export type AssetType = 'ETF' | 'STOCK' | 'CRYPTO' | 'UNKNOWN'

export interface WeightedSlice {
  label: string
  percent: number
}

export interface EtfComposition {
  companies: WeightedSlice[]
  countries: WeightedSlice[]
  sectors: WeightedSlice[]
  source: string | null
  asOf: string | null
}

export interface SecurityInsight {
  ticker: string
  assetType: AssetType
  composition: EtfComposition | null
}

export type ExchangeType = 'BINANCE' | 'KRAKEN'
export type ChainType = 'SOLANA' | 'ETHEREUM' | 'BITCOIN'
export type FinaryMappingAction = 'SKIP' | 'MAP_EXISTING' | 'CREATE_NEW'

export interface ExchangeStatus {
  id: number
  exchangeType: ExchangeType
  status: string
  lastSyncedAt: string | null
}

export interface WalletStatus {
  id: number
  chain: ChainType
  address: string
  label: string | null
  lastSyncedAt: string | null
}

export interface TrSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: string | null
  contact: string | null
}

export interface FinaryAccountPreview {
  finaryId: string
  finaryName: string
  finaryInstitution: string
  finaryCategory: string
  suggestedType: AccountType
  currentBalance: number
  nativeCurrency: string
  transactionCount: number
}

export interface FinaryPreviewResponse {
  accounts: FinaryAccountPreview[]
  existingPicsouAccounts: Account[]
  totalTransactionCount: number
  fileToken: string
  autoMapped?: boolean
  suggestedMappings?: FinaryAccountMapping[]
}

export interface FinaryConnectionStatus {
  connected: boolean
  sessionId: number | null
  status: string | null
  lastSyncedAt: string | null
  maskedEmail: string | null
}

export interface NewAccountDetails {
  name: string
  type: AccountType
  provider?: string
  currency: string
  color?: string
}

export interface FinaryAccountMapping {
  finaryId: string
  finaryName: string
  finaryCategory: string
  action: FinaryMappingAction
  targetAccountId?: number
  newAccount?: NewAccountDetails
}

export interface FinaryImportRequest {
  mappings: FinaryAccountMapping[]
  fileToken: string
}

export interface ImportedAccountSummary {
  id: number
  name: string
  type: AccountType
  currentBalance: number
  color: string
}

export interface FinaryImportResultResponse {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  snapshotsCreated: number
  transactionsImported: number
  importedAccounts: ImportedAccountSummary[]
}

export interface FinaryAutoSyncResponse {
  status: 'OK' | 'NEEDS_MAPPING' | 'TOTP_REQUIRED' | 'NOT_CONNECTED'
  accountsSynced: number
  newAccountCount: number
}

export type RewardKind =
  | 'EARN' | 'STAKING' | 'SUPERCHARGER' | 'AIRDROP' | 'CASHBACK'
  | 'REFERRAL' | 'CAMPAIGN' | 'DEFI_YIELD' | 'OTHER'

export interface Transaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | 'REWARD' | null
  ticker: string | null
  name: string | null
  quantity: number | null
  pricePerUnit: number | null
  rewardKind?: RewardKind | null
}

export interface TransactionRequest {
  date: string          // ISO date "YYYY-MM-DD"
  description: string
  amount: number        // signed: positive=deposit, negative=withdrawal
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | 'REWARD' | null
  ticker?: string
  name?: string
  quantity?: number
  pricePerUnit?: number
  currency?: string
}

export interface CryptoSourceInfo {
  id: string
  label: string
}

/** How (and whether) an asset symbol got linked to aggregator ids — mirrors backend `AssetStatus`. */
export type AssetStatus = 'PENDING' | 'AUTO' | 'USER' | 'WORTHLESS'

/** One asset an aggregator offers for a symbol (best-first by `marketCapRank`). */
export interface ImportAssetCandidate {
  /** That aggregator's own id — a CoinGecko slug, a CoinMarketCap number, a Yahoo symbol. */
  id: string
  name: string | null
  symbol: string | null
  marketCapRank: number | null
}

/**
 * What one aggregator offers for a symbol. Empty `candidates` means it doesn't know the symbol —
 * leave it unpicked and it simply won't price the asset.
 */
export interface ImportAggregatorBlock {
  /** Stable aggregator key: `coingecko`, `coinmarketcap`, `yahoo`, … */
  aggregatorKey: string
  /** That aggregator's dominant match, pre-selected; `null` when ambiguous or unranked. */
  suggestedId: string | null
  candidates: ImportAssetCandidate[]
}

/**
 * A coin the import preview asks the operator to confirm/correct before committing — one block per
 * aggregator available to resolve. Picking an id on several is what lets a second aggregator quote
 * the coin when the first is rate-limited or off; the UI loops over `aggregators`, so a new
 * aggregator needs no frontend change.
 */
export interface ImportAssetChoice {
  symbol: string
  /** Registry status today: `AUTO` (a prior guess), `PENDING` (unresolved), or `null` (unseen). */
  currentStatus: AssetStatus | null
  aggregators: ImportAggregatorBlock[]
}

/** The operator's decision for one previewed coin, sent with the import request. */
export interface ImportAssetMapping {
  symbol: string
  action: 'MAP' | 'WORTHLESS' | 'IGNORE'
  /** `aggregatorKey → picked id`, one entry per aggregator chosen. Used when `action === 'MAP'`. */
  aggregatorIds?: Record<string, string>
  /**
   * A pasted aggregator link (the escape hatch when the searches offered nothing) — resolved
   * server-side by whichever aggregator recognises it; outranks a picked id for that aggregator.
   */
  url?: string
  name?: string
}

/**
 * What one aggregator offers for a symbol in the standing mapping UI, plus what it's mapped to today.
 * Mirrors the import block ({@link ImportAggregatorBlock}) but adds `currentId` — the ref stored right
 * now — so the editor pre-selects the existing mapping.
 */
export interface AssetAggregatorBlock {
  /** Stable aggregator key: `coingecko`, `coinmarketcap`, `yahoo`, … */
  aggregatorKey: string
  /** That aggregator's dominant match, pre-selected on a fresh mapping; `null` when ambiguous/unranked. */
  suggestedId: string | null
  /** The ref stored for this aggregator today; `null` when unmapped. Pre-selected over `suggestedId`. */
  currentId: string | null
  candidates: ImportAssetCandidate[]
}

/**
 * Candidates for one symbol from the standing mapping UI (holding detail, registry table) — the
 * backend `AssetCandidatesResponse`. Served for any symbol, even one already settled, so a mapping
 * can be re-verified outside an import. One block per aggregator, like the import preview: the UI
 * loops over `aggregators`, so a new aggregator needs no frontend change.
 */
export interface AssetCandidatesResponse {
  symbol: string
  currentStatus: AssetStatus | null
  aggregators: AssetAggregatorBlock[]
}

/** A `financial_asset` registry row, returned after applying a standing mapping. */
export interface AssetResponse {
  symbol: string
  name: string | null
  type: string | null
  status: AssetStatus | null
  /** One field per aggregator ref — a non-null one means that aggregator can quote this asset. */
  coingeckoId: string | null
  yahooSymbol: string | null
  coinmarketcapId: string | null
  lastEurValue: number | null
  priceSyncedAt: string | null
}

/** The operator's standing mapping decision for one symbol (holding detail, registry table). */
export interface AssetMappingRequest {
  action: 'MAP' | 'WORTHLESS'
  /** A pasted aggregator asset-page URL; resolved server-side, takes precedence over `aggregatorIds`. */
  url?: string
  /** `aggregatorKey → picked id`, one entry per aggregator chosen. Used when `action === 'MAP'`. */
  aggregatorIds?: Record<string, string>
  name?: string
}

export interface CryptoPreviewResponse {
  fileToken: string
  source: string
  sourceLabel: string
  rowCount: number
  transactionCount: number
  buyCount: number
  sellCount: number
  rewardCount: number
  unknownCount: number
  unvaluedCount: number
  firstDate: string | null
  lastDate: string | null
  currencies: string[]
  nativeCurrency: string
  totalInvested: number
  totalRewards: number
  rewardsByKind: Record<string, number>
  assetChoices: ImportAssetChoice[]
  existingAccounts: Account[]
}

export interface CryptoImportRequest {
  fileToken: string
  action: 'CREATE_NEW' | 'MAP_EXISTING'
  targetAccountId?: number
  accountName?: string
  color?: string
  assetMappings?: ImportAssetMapping[]
}

export interface CryptoImportResult {
  accountId: number
  accountName: string
  source: string
  transactionsImported: number
  holdingsCount: number
  totalRewards: number
}
