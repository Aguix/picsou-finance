/**
 * Per-aggregator display metadata shared by every mapping surface — the import wizard's preview and
 * the standing editor (holding detail, registry). Keeping it here (not in a component) is what lets a
 * new aggregator light up everywhere from one place: add its label and verify-URL builder and both
 * the import pickers and the standing pickers pick it up. Anything not listed still works — the label
 * falls back to the raw key and the row simply gets no verify link.
 */

/** Human label for an aggregator; falls back to the key itself for one we don't know about yet. */
export const AGGREGATOR_LABEL: Record<string, string> = {
  coingecko: 'CoinGecko',
  coinmarketcap: 'CoinMarketCap',
  yahoo: 'Yahoo Finance',
}

export function aggregatorLabel(key: string): string {
  return AGGREGATOR_LABEL[key] ?? key
}

/**
 * Where to go to eyeball a picked id, per aggregator. An aggregator with no entry (or no id-based
 * page) simply gets no verify link — the picker still works, so adding one needs nothing here.
 */
export const VERIFY_URL: Record<string, (id: string) => string> = {
  coingecko: (id) => `https://www.coingecko.com/en/coins/${id}`,
  yahoo: (id) => `https://finance.yahoo.com/quote/${encodeURIComponent(id)}`,
}

/** Build the verify URL for a picked id on an aggregator, or `undefined` when there's no id/mapping. */
export function verifyUrl(aggregatorKey: string, id: string | null | undefined): string | undefined {
  return id ? VERIFY_URL[aggregatorKey]?.(id) : undefined
}
