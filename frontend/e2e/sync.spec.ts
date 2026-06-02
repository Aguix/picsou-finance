import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Sync page tabs', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Synchronisation' }).click()
    await page.waitForURL('**/sync')
  })

  test('should show 5 tabs', async ({ page }) => {
    const tabs = page.locator('[role="tablist"] [role="tab"]')
    await expect(tabs).toHaveCount(5)
  })

  test('should switch to Exchanges tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Exchanges' }).click()
    await page.waitForURL('**/sync?tab=exchanges')
    // Exchanges content should be visible
    await expect(page.getByText('Ajouter un exchange')).toBeVisible()
  })

  test('should switch to Wallets tab', async ({ page }) => {
    await page.getByRole('tab', { name: 'Wallets' }).click()
    await page.waitForURL('**/sync?tab=wallets')
    // Wallets content should be visible
    await expect(page.getByText('Ajouter un wallet')).toBeVisible()
  })

  test('should switch to Finary tab and show wizard step 1', async ({ page }) => {
    await page.getByRole('tab', { name: 'Finary' }).click()
    await page.waitForURL('**/sync?tab=finary')
    // Finary wizard step indicator should be visible
    await expect(page.getByText('Fichier', { exact: true })).toBeVisible()
    // Upload area should be visible
    await expect(page.getByText('Importer un fichier Finary (.xlsx)').first()).toBeVisible()
  })
})
