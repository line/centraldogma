import { test, expect } from '@playwright/test';

test.beforeEach('Login', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByText(/Login/)).toBeVisible();
  await page.getByPlaceholder('ID').fill('foo');
  await page.getByPlaceholder('Password').fill('bar');
  const loginResponse = page.waitForResponse((response) => response.url().includes('/api/v1/login'));
  await page.getByRole('button', { name: 'Login' }).click();
  await loginResponse;
});

test('welcome message', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Welcome to Central Dogma!' })).toBeVisible();
});

test('search project', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByText('Search project ...')).toBeVisible();
  await expect(page.getByRole('combobox')).toBeVisible();
  await page.locator('#home-search').click();
  await expect(page.getByRole('option', { name: 'dogma' })).toBeVisible();
  await expect(page.getByRole('option', { name: 'foo' })).toBeVisible();
});
