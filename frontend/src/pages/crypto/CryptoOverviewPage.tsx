import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Bitcoin, Globe, Link2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { PageHeader } from '@/components/shared/PageHeader'
import { EmptyState } from '@/components/shared/EmptyState'
import { CryptoStatsView } from '@/components/shared/CryptoStatsSection'
import { useConsolidatedCryptoStats, useCryptoStats } from '@/features/crypto/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { CoinMappingsDialog } from './CoinMappingsDialog'
import type { Account } from '@/types/api'

/**
 * The crypto portfolio page, with two levels of detail:
 * - **Global**: every coin pooled across all of the member's CRYPTO accounts (imports, API-synced
 *   exchanges, on-chain wallets) — total value, invested, and how much comes from earned coins.
 * - **Per exchange/wallet**: the same view scoped to one account, where rewards are detailed by
 *   program (Earn, staking, DeFi, Supercharger, Airdrop Arena, cashback, campagnes…).
 *
 * The selected view lives in the `?account=` search param so the import flow can deep-link to a
 * freshly imported account.
 */
export function CryptoOverviewPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  const { data: accounts } = useAccounts()
  const cryptoAccounts = (accounts ?? []).filter((a: Account) => a.type === 'CRYPTO')

  const accountParam = searchParams.get('account')
  const selectedId = accountParam ? Number(accountParam) : null
  const selected = cryptoAccounts.find((a) => a.id === selectedId) ?? null

  const consolidated = useConsolidatedCryptoStats()
  const perAccount = useCryptoStats(selected?.id ?? NaN, selected != null)
  const [mappingsOpen, setMappingsOpen] = useState(false)

  const { data, isLoading } = selected ? perAccount : consolidated
  const hasData = !!data && Array.isArray(data.assets) && data.assets.length > 0

  function select(account: Account | null) {
    setSearchParams(account ? { account: String(account.id) } : {}, { replace: true })
  }

  return (
    <div>
      <PageHeader
        surtitle={t('crypto.surtitle', 'Portefeuille')}
        title={t('crypto.title', 'Mes cryptos')}
        actions={
          <Button variant="outline" size="sm" onClick={() => setMappingsOpen(true)}>
            <Link2 />
            {t('crypto.mappings.title', 'Correspondances CoinGecko')}
          </Button>
        }
      />
      <CoinMappingsDialog open={mappingsOpen} onOpenChange={setMappingsOpen} />

      {/* Global ↔ per-exchange selector */}
      {cryptoAccounts.length > 0 && (
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Button
            variant={selected == null ? 'default' : 'outline'}
            size="sm"
            onClick={() => select(null)}
          >
            <Globe />
            {t('crypto.globalView', 'Vue globale')}
          </Button>
          {cryptoAccounts.map((a) => (
            <Button
              key={a.id}
              variant={selected?.id === a.id ? 'default' : 'outline'}
              size="sm"
              onClick={() => select(a)}
            >
              {a.name}
              {a.provider && a.provider !== a.name && (
                <Badge variant="secondary" className="ml-1">{a.provider}</Badge>
              )}
            </Button>
          ))}
        </div>
      )}

      {!isLoading && !hasData ? (
        <EmptyState
          icon={<Bitcoin className="size-12" />}
          title={selected
            ? t('crypto.emptyAccount.title', 'Aucune donnée pour ce compte')
            : t('crypto.empty.title', 'Aucune crypto')}
          description={selected
            ? t(
                'crypto.emptyAccount.desc',
                "Ce compte n'a pas encore de positions ni de transactions crypto importées.",
              )
            : t(
                'crypto.empty.desc',
                'Importez un export CSV (Crypto.com, Kraken, Binance, Bybit, Bitstack, Ledger…), connectez un exchange ou un wallet pour voir vos cryptos consolidées ici.',
              )}
          action={{
            label: t('crypto.empty.action', 'Ajouter une source crypto'),
            onClick: () => navigate('/sync?tab=crypto-import'),
          }}
        />
      ) : (
        <CryptoStatsView
          data={data}
          isLoading={isLoading}
          title={selected
            ? t('crypto.accountTitle', {
                defaultValue: '{{name}} — détail par programme',
                name: selected.name,
              })
            : t('crypto.consolidatedTitle', 'Toutes plateformes & wallets')}
        />
      )}
    </div>
  )
}
