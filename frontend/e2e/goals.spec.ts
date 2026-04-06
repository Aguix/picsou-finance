import { test, expect } from '@playwright/test'
import { login } from './helpers'

test.describe('Goals page', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
    await page.getByRole('link', { name: 'Objectifs' }).click()
    await page.waitForURL('**/goals')
  })

  test('should show goal cards or empty state', async ({ page }) => {
    // Either goal cards are visible or the empty state message
    const hasGoals = (await page.locator('.grid > *').count()) > 0
    if (hasGoals) {
      // Goal cards with progress bars
      await expect(page.locator('.grid').first()).toBeVisible()
    } else {
      await expect(page.getByText('Aucun objectif défini')).toBeVisible()
    }
  })

  test('should open add goal dialog', async ({ page }) => {
    await page.getByRole('button', { name: 'Nouvel objectif' }).click()
    // Dialog should appear with form fields
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByLabel('Montant cible')).toBeVisible()
    // Close dialog
    await page.getByRole('button', { name: 'Annuler' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })
})
