import { clsx } from 'clsx'
import type { ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}

export function formatCurrency(value: number, currency: string = 'EUR', locale: string = 'fr-FR'): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

export function formatPercent(value: number, decimals = 2): string {
  return (value * 100).toFixed(decimals) + '%'
}

export function formatTimeAgo(dateStr: string, locale: string = 'fr-FR'): string {
  const seconds = Math.floor((Date.now() - new Date(dateStr).getTime()) / 1000)
  if (seconds < 60) return locale.startsWith('fr') ? "à l'instant" : 'just now'
  if (seconds < 3600) {
    const m = Math.floor(seconds / 60)
    return locale.startsWith('fr') ? `il y a ${m}m` : `${m}m ago`
  }
  const h = Math.floor(seconds / 3600)
  return locale.startsWith('fr') ? `il y a ${h}h` : `${h}h ago`
}

/** Format a number as EUR currency */
export function formatEur(value: number, opts?: { compact?: boolean }): string {
  if (opts?.compact && Math.abs(value) >= 1000) {
    return new Intl.NumberFormat('fr-FR', {
      style: 'currency',
      currency: 'EUR',
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(value)
  }
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/** Format a date string as "12 mars 2026" */
export function formatDate(dateStr: string, locale: string = 'fr-FR'): string {
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(dateStr))
}

/** Today as "Lundi 24 mars 2026" */
export function todayLabel(): string {
  return new Intl.DateTimeFormat('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date()).replace(/^./, c => c.toUpperCase())
}

/** Format a LocalDate ("2026-03-24") as "24 mars 2026" */
export function formatLocalDate(dateStr: string): string {
  const [y, m, d] = dateStr.split('-').map(Number)
  return new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(y, m - 1, d))
}

export function accountTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    LEP: 'LEP',
    PEA: 'PEA',
    COMPTE_TITRES: 'Compte-titres',
    CRYPTO: 'Crypto',
    CHECKING: 'Compte courant',
    SAVINGS: 'Épargne',
    OTHER: 'Autre',
  }
  return labels[type] ?? type
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

/**
 * Validates that a redirect URL is safe (relative, same-origin).
 * Returns '/' for any absolute URL or protocol-relative URL to prevent open redirects.
 */
export function safeRedirect(url: string | null | undefined): string {
  if (!url) return '/'
  // Must start with exactly one slash (not // which is protocol-relative)
  if (/^\/(?!\/)/.test(url)) return url
  return '/'
}
