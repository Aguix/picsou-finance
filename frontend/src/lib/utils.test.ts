import { describe, it, expect } from 'vitest'
import { cn, formatCurrency, formatDate, formatPercent } from './utils'

describe('cn', () => {
  it('merges class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('handles conditional classes', () => {
    expect(cn('foo', false && 'bar', 'baz')).toBe('foo baz')
  })

  it('merges tailwind conflicts', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })
})

describe('formatCurrency', () => {
  it('formats EUR in French locale', () => {
    const result = formatCurrency(1234.5, 'EUR', 'fr-FR')
    expect(result).toContain('1')
    expect(result).toContain('234')
  })

  it('formats zero', () => {
    const result = formatCurrency(0, 'EUR', 'fr-FR')
    expect(result).toContain('0')
  })

  it('formats negative values', () => {
    const result = formatCurrency(-500, 'EUR', 'fr-FR')
    expect(result).toContain('500')
  })
})

describe('formatDate', () => {
  it('formats ISO date string', () => {
    const result = formatDate('2025-03-15', 'fr-FR')
    expect(result).toBeTruthy()
    expect(typeof result).toBe('string')
  })
})

describe('formatPercent', () => {
  it('formats percentage', () => {
    const result = formatPercent(0.5)
    expect(result).toContain('50')
  })
})
