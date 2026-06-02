import { useState } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { NumericInput } from '@/components/shared/NumericInput'
import { Label } from '@/components/ui/label'
import { parseAmount } from '@/lib/utils'
import { Loader2 } from 'lucide-react'
import type { HoldingResponse } from '@/types/api'

interface EditHoldingModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  holding: HoldingResponse | null
  onSubmit: (ticker: string, quantity: number, averageBuyIn?: number) => Promise<void>
  isLoading?: boolean
}

export function EditHoldingModal({ open, onOpenChange, holding, onSubmit, isLoading }: EditHoldingModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Modifier {holding?.ticker}</DialogTitle>
        </DialogHeader>
        {/* Remount per holding so the form's initial values come straight from
            props — no populate-on-open effect needed. */}
        {open && holding && (
          <HoldingForm
            key={holding.ticker}
            holding={holding}
            onOpenChange={onOpenChange}
            onSubmit={onSubmit}
            isLoading={isLoading}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

interface HoldingFormProps {
  holding: HoldingResponse
  onOpenChange: (open: boolean) => void
  onSubmit: (ticker: string, quantity: number, averageBuyIn?: number) => Promise<void>
  isLoading?: boolean
}

function HoldingForm({ holding, onOpenChange, onSubmit, isLoading }: HoldingFormProps) {
  const [quantity, setQuantity] = useState(() => String(holding.quantity))
  const [averageBuyIn, setAverageBuyIn] = useState(() => (holding.averageBuyIn != null ? String(holding.averageBuyIn) : ''))
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await onSubmit(
        holding.ticker,
        parseAmount(quantity),
        averageBuyIn ? parseAmount(averageBuyIn) : undefined,
      )
      onOpenChange(false)
    } catch {
      setError('Une erreur est survenue. Veuillez réessayer.')
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1">
        <Label>Quantité</Label>
        <NumericInput
          value={quantity}
          onChange={e => setQuantity(e.target.value)}
          required
        />
      </div>
      <div className="space-y-1">
        <Label>Prix moyen d'achat (€) <span className="text-muted-foreground text-xs">(optionnel)</span></Label>
        <NumericInput
          value={averageBuyIn}
          onChange={e => setAverageBuyIn(e.target.value)}
          placeholder="—"
        />
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>Annuler</Button>
        <Button type="submit" disabled={isLoading}>
          {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          Enregistrer
        </Button>
      </DialogFooter>
    </form>
  )
}
