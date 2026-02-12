/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

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

test.describe('Project Tokens (appIds)', () => {
  test('should navigate to project tokens settings page', async ({ page }) => {
    // Navigate to the dogma project settings
    await page.goto('/app/projects/dogma/settings/tokens');

    // Verify we're on the tokens settings page
    await expect(page.getByRole('heading', { name: 'dogma' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Tokens' })).toBeVisible();
  });

  test('should display tokens tab in project settings', async ({ page }) => {
    // Navigate to project settings
    await page.goto('/app/projects/dogma/settings');

    // Verify tokens tab exists
    const tokensTab = page.getByRole('tab', { name: 'Tokens' });
    await expect(tokensTab).toBeVisible();

    // Click on tokens tab
    await tokensTab.click();

    // Verify URL changed to tokens page
    await expect(page).toHaveURL(/\/app\/projects\/dogma\/settings\/tokens/);
  });

  test('should display Add Token button', async ({ page }) => {
    await page.goto('/app/projects/dogma/settings/tokens');

    // Verify Add Token button is visible
    await expect(page.getByRole('button', { name: /Add Token/i })).toBeVisible();
  });

  test('should display token list table with correct columns', async ({ page }) => {
    await page.goto('/app/projects/dogma/settings/tokens');

    // Wait for the page to load
    await expect(page.getByRole('tab', { name: 'Tokens' })).toBeVisible();

    // The table should be present
    const table = page.locator('table');
    await expect(table).toBeVisible();

    // Verify table has correct column headers (4 columns)
    const headers = table.locator('th');
    await expect(headers).toHaveCount(4);

    // Verify column header names
    await expect(headers.nth(0)).toContainText('App ID');
    await expect(headers.nth(1)).toContainText('Added By');
    await expect(headers.nth(2)).toContainText('Added At');
    await expect(headers.nth(3)).toContainText('Actions');
  });

  test('should open add token popover when clicking Add Token button', async ({ page }) => {
    await page.goto('/app/projects/dogma/settings/tokens');

    // Click the Add Token button
    await page.getByRole('button', { name: /Add Token/i }).click();

    // Verify popover opens with expected content
    await expect(page.getByText(/Add a new token/i)).toBeVisible();
  });

  test('should show token role options in add token popover', async ({ page }) => {
    await page.goto('/app/projects/dogma/settings/tokens');

    // Click the Add Token button
    await page.getByRole('button', { name: /Add Token/i }).click();

    // Wait for popover to be visible
    await expect(page.getByText(/Add a new token/i)).toBeVisible();

    // Check for role selection - should have Member and Owner radio options
    await expect(page.getByRole('radio', { name: 'Member' })).toBeVisible();
    await expect(page.getByRole('radio', { name: 'Owner' })).toBeVisible();
  });

  test('should navigate to repository tokens settings page', async ({ page }) => {
    // Navigate to repository tokens settings
    await page.goto('/app/projects/dogma/repos/meta/settings/tokens');

    // Verify we're on the repository tokens settings page
    await expect(page.getByRole('heading', { name: 'meta' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Tokens' })).toBeVisible();
  });

  test('should display tokens tab in repository settings', async ({ page }) => {
    // Navigate to repository settings
    await page.goto('/app/projects/dogma/repos/meta/settings');

    // Verify tokens tab exists
    const tokensTab = page.getByRole('tab', { name: 'Tokens' });
    await expect(tokensTab).toBeVisible();

    // Click on tokens tab
    await tokensTab.click();

    // Verify URL changed to tokens page
    await expect(page).toHaveURL(/\/app\/projects\/dogma\/repos\/meta\/settings\/tokens/);
  });
});

test.describe('Project Metadata API', () => {
  test('should return tokens/appIds in project metadata API response', async ({ request }) => {
    // Make API request to get project metadata
    const response = await request.get('/api/v1/projects/dogma');

    expect(response.ok()).toBeTruthy();

    const metadata = await response.json();

    // Verify the response structure
    expect(metadata).toHaveProperty('name');
    expect(metadata).toHaveProperty('repos');
    expect(metadata).toHaveProperty('members');
    const hasTokensField = 'appIds' in metadata;
    expect(hasTokensField).toBeTruthy();
    expect(metadata).toHaveProperty('creation');

    // Verify tokens/appIds is an object (can be empty)
    const tokensOrAppIds = metadata.tokens || metadata.appIds;
    expect(typeof tokensOrAppIds).toBe('object');
  });

  test('should have correct structure for token entries', async ({ request }) => {
    const response = await request.get('/api/v1/projects/dogma');

    expect(response.ok()).toBeTruthy();

    const metadata = await response.json();
    // The server returns 'tokens' field which is mapped to 'appIds' in the frontend DTO
    const tokensOrAppIds = metadata.tokens || metadata.appIds || {};

    // If there are any tokens/appIds, verify their structure
    const tokenEntries = Object.values(tokensOrAppIds);
    for (const entry of tokenEntries) {
      const token = entry as { appId: string; role: string; creation: { user: string; timestamp: string } };
      expect(token).toHaveProperty('appId');
      expect(token).toHaveProperty('role');
      expect(token).toHaveProperty('creation');
      expect(['MEMBER', 'OWNER']).toContain(token.role);
      expect(token.creation).toHaveProperty('user');
      expect(token.creation).toHaveProperty('timestamp');
    }
  });
});
