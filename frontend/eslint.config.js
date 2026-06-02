import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
  {
    // shadcn/ui components are vendor-generated (CLI-managed) and intentionally
    // export a component alongside its `*Variants` cva object. That dual export
    // trips react-refresh/only-export-components — a known shadcn false positive.
    // We must not hand-edit these files (a future `shadcn add` would wipe it),
    // so the rule is scoped off here instead.
    files: ['src/components/ui/**'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
])
