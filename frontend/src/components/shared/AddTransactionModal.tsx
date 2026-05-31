import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { Label } from '@/components/ui/label'
import { parseAmount } from '@/lib/utils'
import { Loader2 } from 'lucide-react'
import type { AccountType, TransactionRequest } from '@/types/api'
import { accountsApi } from '@/features/accounts/api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

const INVESTMENT_TYPES: AccountType[] = ['PEA', 'COMPTE_TITRES', 'CRYPTO']

interface AddTransactionModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  accountId: number
  accountType: AccountType
  onSubmit: (data: TransactionRequest) => Promise<void>
  isLoading?: boolean
  initialValues?: TransactionRequest & { id?: number }
}

export function AddTransactionModal({ open, onOpenChange, accountId, accountType, onSubmit, isLoading, initialValues }: AddTransactionModalProps) {
  const isInvestment = INVESTMENT_TYPES.includes(accountType)

  const { data: holdings } = useQuery({
    queryKey: ['accounts', accountId, 'holdings'],
    queryFn: () => accountsApi.holdings(accountId),
    staleTime: QUERY_STALE_TIMES.accountDetail,
    enabled: isInvestment && !!accountId,
  })

  // Shared state
  const [date, setDate] = useState(new Date().toISOString().split('T')[0])
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)

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

  // Reset or populate form when modal opens/closes
  useEffect(() => {
    if (open && initialValues) {
      setDate(initialValues.date ? String(initialValues.date) : new Date().toISOString().split('T')[0])
      if (initialValues.txType === 'BUY' || initialValues.txType === 'SELL') {
        setInvestType(initialValues.txType)
        setTicker(initialValues.ticker ?? '')
        setName(initialValues.description ?? '')
        setQuantity(initialValues.quantity != null ? String(initialValues.quantity) : '')
        setPricePerUnit(initialValues.pricePerUnit != null ? String(initialValues.pricePerUnit) : '')
      } else {
        setTxDirection(initialValues.amount != null && Number(initialValues.amount) >= 0 ? 'deposit' : 'withdrawal')
        setDescription(initialValues.description ?? '')
        setCashAmount(initialValues.amount != null ? String(Math.abs(Number(initialValues.amount))) : '')
      }
    } else if (!open) {
      setDate(new Date().toISOString().split('T')[0])
      setDescription('')
      setError(null)
      setTxDirection('deposit')
      setCashAmount('')
      setInvestType('BUY')
      setTicker('')
      setName('')
      setQuantity('')
      setPricePerUnit('')
    }
  }, [open])

  const total = quantity && pricePerUnit
    ? (parseAmount(quantity) * parseAmount(pricePerUnit)).toFixed(2)
    : '—'

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    let data: TransactionRequest

    if (isInvestment) {
      const qty = parseAmount(quantity)
      const price = parseAmount(pricePerUnit)
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
      const raw = parseAmount(cashAmount)
      const amount = txDirection === 'deposit' ? Math.abs(raw) : -Math.abs(raw)
      data = {
        date,
        description,
        amount,
        txType: txDirection === 'deposit' ? 'DEPOSIT' : 'WITHDRAWAL',
      }
    }

    try {
      await onSubmit(data)
      onOpenChange(false)
    } catch {
      setError('Une erreur est survenue. Veuillez réessayer.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{initialValues ? 'Modifier la transaction' : 'Ajouter une transaction'}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Date */}
          <div className="space-y-1">
            <Label>Date</Label>
            <DateInput value={date} onChange={setDate} required />
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
                <NumericInput value={quantity} onChange={e => setQuantity(e.target.value)} required />
              </div>
              <div className="space-y-1">
                <Label>Prix unitaire (€)</Label>
                <NumericInput value={pricePerUnit} onChange={e => setPricePerUnit(e.target.value)} required />
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
                <NumericInput value={cashAmount} onChange={e => setCashAmount(e.target.value)} required />
              </div>
            </>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Annuler</Button>
            <Button type="submit" disabled={isLoading}>
              {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {initialValues ? 'Enregistrer' : 'Ajouter'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
