export type Theme = 'light' | 'dark' | 'system'
export type Locale = 'fr' | 'en'

export interface NavItem {
  labelKey: string
  path: string
  icon: string
}
