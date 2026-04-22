import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2 } from 'lucide-react'
import type { AccountType, TransactionRequest } from '@/types/api'
import { useAccountHoldings } from '@/features/accounts/hooks'

const INVESTMENT_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO']

interface AddTransactionModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
}

export function AddTransactionModal({ open, onOpenChange, accountId, accountType, onSubmit, isLoading }: AddTransactionModalProps) {
  const { t } = useTranslation()
  const isInvestment = INVESTMENT_TYPES.includes(accountType)
  const { data: holdings } = useAccountHoldings(isInvestment ? accountId : 0)

  // Shared state
  const [date, setDate] = useState(new Date().toISOString().split('T')[0])
  const [description, setDescription] = useState('')

  // Cash fields
  const [txDirection, setTxDirection] = useState<'deposit' | 'withdrawal'>('deposit')
  const [cashAmount, setCashAmount] = useState('')

  // Investment fields
  const [investType, setInvestType] = useState<'BUY' | 'SELL'>('BUY')
  const [ticker, setTicker] = useState('')
  const [name, setName] = useState('')
  const [quantity, setQuantity] = useState('')
  const [pricePerUnit, setPricePerUnit] = useState('')

  // Auto-fill name from existing holdings when ticker matches
  useEffect(() => {
    if (!holdings || !ticker) return
    const match = holdings.find(h => h.ticker.toUpperCase() === ticker.toUpperCase())
    if (match?.name) setName(match.name)
  }, [ticker, holdings])

  const total = quantity && pricePerUnit
    ? (parseFloat(quantity) * parseFloat(pricePerUnit)).toFixed(2)
    : '—'

  function reset() {
    setDate(new Date().toISOString().split('T')[0])
    setDescription('')
    setTxDirection('deposit')
    setCashAmount('')
    setInvestType('BUY')
    setTicker('')
    setName('')
    setQuantity('')
    setPricePerUnit('')
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    let data: TransactionRequest

    if (isInvestment) {
      const qty = parseFloat(quantity)
      const price = parseFloat(pricePerUnit)
      // amount: positive for SELL (receiving), negative for BUY (spending)
      const amount = investType === 'BUY' ? -(qty * price) : (qty * price)
      data = {
        date,
        description: name || (investType === 'BUY' ? `Achat ${ticker}` : `Vente ${ticker}`),
        amount,
        txType: investType,
        ticker: ticker.toUpperCase(),
        quantity: qty,
        pricePerUnit: price,
      }
    } else {
      const raw = parseFloat(cashAmount)
      const amount = txDirection === 'deposit' ? Math.abs(raw) : -Math.abs(raw)
      data = {
        date,
        description,
        amount,
        txType: txDirection === 'deposit' ? 'DEPOSIT' : 'WITHDRAWAL',
      }
    }

    await onSubmit(data)
    reset()
    onOpenChange(false)
  }

  void t

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Ajouter une transaction</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Date */}
          <div className="space-y-1">
            <Label>Date</Label>
            <Input type="date" value={date} onChange={e => setDate(e.target.value)} required />
          </div>

          {isInvestment ? (
            <>
              {/* BUY / SELL toggle */}
              <div className="flex gap-2">
                {(['BUY', 'SELL'] as const).map(type => (
                  <Button
                    key={type}
                    type="button"
                    variant={investType === type ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setInvestType(type)}
                  >
                    {type === 'BUY' ? 'Achat' : 'Vente'}
                  </Button>
                ))}
              </div>
              <div className="space-y-1">
                <Label>Ticker</Label>
                <Input placeholder="BTC, IWDA.AS…" value={ticker} onChange={e => setTicker(e.target.value)} required />
              </div>
              <div className="space-y-1">
                <Label>Nom <span className="text-muted-foreground text-xs">(optionnel)</span></Label>
                <Input placeholder="Ex : iShares Core MSCI World" value={name} onChange={e => setName(e.target.value)} />
              </div>
              <div className="space-y-1">
                <Label>Quantité</Label>
                <Input type="number" step="any" min="0" value={quantity} onChange={e => setQuantity(e.target.value)} required />
              </div>
              <div className="space-y-1">
                <Label>Prix unitaire (€)</Label>
                <Input type="number" step="any" min="0" value={pricePerUnit} onChange={e => setPricePerUnit(e.target.value)} required />
              </div>
              <p className="text-sm text-muted-foreground">Total : {total} €</p>
            </>
          ) : (
            <>
              {/* DEPOSIT / WITHDRAWAL toggle */}
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant={txDirection === 'deposit' ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => setTxDirection('deposit')}
                >
                  + Dépôt
                </Button>
                <Button
                  type="button"
                  variant={txDirection === 'withdrawal' ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => setTxDirection('withdrawal')}
                >
                  − Retrait
                </Button>
              </div>
              <div className="space-y-1">
                <Label>Description</Label>
                <Input value={description} onChange={e => setDescription(e.target.value)} required />
              </div>
              <div className="space-y-1">
                <Label>Montant (€)</Label>
                <Input type="number" step="0.01" min="0" value={cashAmount} onChange={e => setCashAmount(e.target.value)} required />
              </div>
            </>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Annuler</Button>
            <Button type="submit" disabled={isLoading}>
              {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Ajouter
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
