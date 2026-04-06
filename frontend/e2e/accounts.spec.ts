import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Accounts page', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Comptes' }).click()
    await page.waitForURL('**/accounts')
  })

  test('should show account cards grid', async ({ page }) => {
    // Account grid should be visible
    await expect(page.locator('.grid').first()).toBeVisible()
  })

  test('should filter accounts by type tabs', async ({ page }) => {
    // Click the PEA filter tab
    await page.getByRole('tab', { name: 'PEA' }).click()
    // The total row should still be visible
    await expect(page.getByText('Total')).toBeVisible()

    // Click "Tous les types" to reset
    await page.getByRole('tab', { name: 'Tous les types' }).click()
    await expect(page.locator('.grid').first()).toBeVisible()
  })

  test('should open add account dialog', async ({ page }) => {
    await page.getByRole('button', { name: 'Ajouter un compte' }).click()
    // Dialog should appear
    await expect(page.getByRole('dialog')).toBeVisible()
    // Close dialog
    await page.getByRole('button', { name: 'Annuler' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})
