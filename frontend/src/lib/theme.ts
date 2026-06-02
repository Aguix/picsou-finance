export type Theme = 'light' | 'dark' | 'system'

export function getStoredTheme(): Theme {
  return (localStorage.getItem('theme') as Theme) ?? 'system'
}

export function applyTheme(theme: Theme) {
  const root = document.documentElement
  if (theme === 'dark') {
    root.classList.add('dark')
  } else if (theme === 'light') {
    root.classList.remove('dark')
  } else {
    root.classList.toggle('dark', window.matchMedia('(prefers-color-scheme: dark)').matches)
  }
  localStorage.setItem('theme', theme)
}

export function initSystemThemeListener() {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (getStoredTheme() === 'system') applyTheme('system')
  })
}
