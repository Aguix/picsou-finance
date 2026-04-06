import type { Transaction } from '@/types/api'

export const mockTransactions: Record<number, Transaction[]> = {
  1: [
    { id: 50, date: '2025-03-15', description: 'Versement LEP', amount: 200.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 51, date: '2025-03-01', description: 'Versement LEP', amount: 200.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 52, date: '2025-02-01', description: 'Versement LEP', amount: 200.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 53, date: '2025-01-15', description: 'Versement initial', amount: 7200.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
  2: [
    { id: 30, date: '2025-03-14', description: 'Achat NVDA', amount: -4800.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 31, date: '2025-03-10', description: 'Achat AAPL', amount: -2325.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 32, date: '2025-03-05', description: 'Achat MSFT', amount: -2720.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 33, date: '2025-02-20', description: 'Achat AMZN', amount: -2900.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 34, date: '2025-02-01', description: 'Versement PEA', amount: 10000.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
  3: [
    { id: 40, date: '2025-03-13', description: 'Achat IWDA', amount: -1800.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 41, date: '2025-03-01', description: 'Achat EUNL', amount: -1140.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 42, date: '2025-02-15', description: 'Dividendes IWDA', amount: 85.0, type: 'credit', category: 'income', nativeCurrency: 'EUR' },
    { id: 43, date: '2025-01-20', description: 'Versement CT', amount: 5000.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
  4: [
    { id: 1, date: '2025-03-15', description: 'Carrefour Market', amount: -67.8, type: 'debit', category: 'groceries', nativeCurrency: 'EUR' },
    { id: 2, date: '2025-03-14', description: 'Salaire', amount: 2800.0, type: 'credit', category: 'income', nativeCurrency: 'EUR' },
    { id: 3, date: '2025-03-13', description: 'EDF Électricité', amount: -85.0, type: 'debit', category: 'utilities', nativeCurrency: 'EUR' },
    { id: 4, date: '2025-03-12', description: 'Amazon.fr', amount: -34.99, type: 'debit', category: 'shopping', nativeCurrency: 'EUR' },
    { id: 5, date: '2025-03-10', description: 'Boulangerie Paul', amount: -8.5, type: 'debit', category: 'food', nativeCurrency: 'EUR' },
    { id: 6, date: '2025-03-08', description: 'Fnac', amount: -29.99, type: 'debit', category: 'shopping', nativeCurrency: 'EUR' },
    { id: 7, date: '2025-03-05', description: 'Loyer', amount: -850.0, type: 'debit', category: 'housing', nativeCurrency: 'EUR' },
    { id: 8, date: '2025-03-01', description: 'Virement épargne', amount: -200.0, type: 'debit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
  5: [
    { id: 10, date: '2025-03-14', description: 'Loyer', amount: -850.0, type: 'debit', category: 'housing', nativeCurrency: 'EUR' },
    { id: 11, date: '2025-03-12', description: 'Carrefour', amount: -45.3, type: 'debit', category: 'groceries', nativeCurrency: 'EUR' },
    { id: 12, date: '2025-03-10', description: 'Virement reçu', amount: 200.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 13, date: '2025-03-08', description: 'Netflix', amount: -17.99, type: 'debit', category: 'entertainment', nativeCurrency: 'EUR' },
    { id: 14, date: '2025-03-05', description: 'SNCF', amount: -52.0, type: 'debit', category: 'transport', nativeCurrency: 'EUR' },
  ],
  6: [
    { id: 60, date: '2025-03-15', description: 'Achat BTC', amount: -1664.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 61, date: '2025-03-12', description: 'Achat ETH', amount: -2160.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 62, date: '2025-03-08', description: 'Achat SOL', amount: -1425.0, type: 'debit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 63, date: '2025-02-25', description: 'Vente DOGE', amount: 320.0, type: 'credit', category: 'investment', nativeCurrency: 'EUR' },
    { id: 64, date: '2025-01-10', description: 'Versement initial', amount: 5000.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
  7: [
    { id: 70, date: '2025-03-01', description: 'Versement Livret A', amount: 300.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 71, date: '2025-02-01', description: 'Versement Livret A', amount: 300.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 72, date: '2025-01-01', description: 'Versement Livret A', amount: 300.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
    { id: 73, date: '2024-12-15', description: 'Intérêts annuels', amount: 82.4, type: 'credit', category: 'income', nativeCurrency: 'EUR' },
    { id: 74, date: '2024-06-01', description: 'Versement initial', amount: 4000.0, type: 'credit', category: 'transfer', nativeCurrency: 'EUR' },
  ],
}
