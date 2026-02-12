import { test, expect } from '@playwright/test';

test.beforeEach('Login', async ({ page }) => {
  await page.goto('/');

  // Wait for page to fully load
  await page.waitForLoadState('networkidle');

  await expect(page.getByText(/Login/)).toBeVisible({ timeout: 10000 });
  await page.getByPlaceholder('ID').fill('foo');
  await page.getByPlaceholder('Password').fill('bar');
  await page.getByRole('button', { name: 'Login' }).click();

  // Wait for login to complete
  await expect(page.getByRole('heading', { name: 'Welcome to Central Dogma!' })).toBeVisible({
    timeout: 10000,
  });
});

test('welcome message', async ({ page }) => {
  await expect(page.getByRole('heading', { name: 'Welcome to Central Dogma!' })).toBeVisible();
});

test('search project', async ({ page }) => {
  // Wait for the search box to be visible
  await expect(page.getByText('Search project ...')).toBeVisible();
  await expect(page.getByRole('combobox')).toBeVisible();

  // Click on the search box using the correct id
  await page.locator('#home-search').click();

  // Wait for options to load and verify they're visible
  await expect(page.getByRole('option', { name: 'dogma' })).toBeVisible({ timeout: 10000 });
  await expect(page.getByRole('option', { name: 'foo' })).toBeVisible();
});
