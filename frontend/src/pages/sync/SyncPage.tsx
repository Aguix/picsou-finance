import { useTranslation } from 'react-i18next'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageHeader } from '@/components/shared/PageHeader'
import { BankSyncTab } from './BankSyncTab'
import { CryptoExchangeTab } from './CryptoExchangeTab'
import { CryptoWalletTab } from './CryptoWalletTab'
import { TradeRepublicTab } from './TradeRepublicTab'
import { FinaryTab } from './FinaryTab'

export function SyncPage() {
  const { t } = useTranslation()

  return (
    <div className="space-y-6">
      <PageHeader title={t('sync.title')} />
      <Tabs defaultValue="banks">
        <TabsList>
          <TabsTrigger value="banks">{t('sync.banks.title')}</TabsTrigger>
          <TabsTrigger value="exchanges">{t('sync.exchanges.title')}</TabsTrigger>
          <TabsTrigger value="wallets">{t('sync.wallets.title')}</TabsTrigger>
          <TabsTrigger value="tr">{t('sync.tr.title')}</TabsTrigger>
          <TabsTrigger value="finary">{t('sync.finary.title')}</TabsTrigger>
        </TabsList>
        <TabsContent value="banks" className="mt-6">
          <BankSyncTab />
        </TabsContent>
        <TabsContent value="exchanges" className="mt-6">
          <CryptoExchangeTab />
        </TabsContent>
        <TabsContent value="wallets" className="mt-6">
          <CryptoWalletTab />
        </TabsContent>
        <TabsContent value="tr" className="mt-6">
          <TradeRepublicTab />
        </TabsContent>
        <TabsContent value="finary" className="mt-6">
          <FinaryTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}
