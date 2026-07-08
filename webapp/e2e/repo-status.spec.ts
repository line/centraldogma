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

import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

// The Shiro test backend authorizes `foo` as a system administrator.
const BACKEND = 'http://127.0.0.1:36462';
const READ_ONLY_REPOS_API = '/api/v1/status/repos/read-only';

// A project marked read-only via the API (project scope) drives the list/API assertions.
const SEED_PROJECT = 'repo-status-e2e';
// A dedicated writable repository that the UI form flips to read-only.
const FORM_PROJECT = 'repo-status-form-e2e';
const FORM_REPO = 'target';

type RepositoryStatus = {
  projectName: string;
  repoName: string;
  status: 'WRITABLE' | 'READ_ONLY';
  updatedAt?: string;
};

let api: APIRequestContext;
let csrfToken: string;

async function readOnlyList(): Promise<RepositoryStatus[]> {
  const res = await api.get(READ_ONLY_REPOS_API);
  // An empty result is returned as 204 No Content.
  if (res.status() === 204) {
    return [];
  }
  expect(res.ok()).toBeTruthy();
  return res.json();
}

async function setRepoStatus(project: string, repo: string, status: 'READ_ONLY' | 'WRITABLE') {
  const res = await api.put(`/api/v1/projects/${project}/repos/${repo}/status`, {
    headers: { 'X-CSRF-Token': csrfToken },
    data: { status },
  });
  // A redundant change (already in that status) is accepted too.
  expect(res.status(), `set ${status}: ${res.status()}`).toBeLessThan(400);
}

async function ensureProject(name: string) {
  const res = await api.post('/api/v1/projects', { headers: { 'X-CSRF-Token': csrfToken }, data: { name } });
  // 200/201 when created, 409 when it already exists from a previous run.
  expect([200, 201, 409]).toContain(res.status());
}

async function ensureRepo(project: string, repo: string) {
  const res = await api.post(`/api/v1/projects/${project}/repos`, {
    headers: { 'X-CSRF-Token': csrfToken },
    data: { name: repo },
  });
  expect([200, 201, 409]).toContain(res.status());
}

async function loginUi(page: Page) {
  await page.goto('/');
  await expect(page.getByText(/Login/)).toBeVisible({ timeout: 10000 });
  await page.getByPlaceholder('ID').fill('foo');
  await page.getByPlaceholder('Password').fill('bar');
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome to Central Dogma!' })).toBeVisible({
    timeout: 10000,
  });
}

// chakra-react-select: click the control, type to filter, and pick the first match.
async function pickOption(page: Page, containerId: string, text: string) {
  await page.locator(`#${containerId}`).click();
  await page.keyboard.type(text);
  await page.waitForTimeout(500);
  await page.keyboard.press('Enter');
}

test.describe.serial('Repository Status', () => {
  test.beforeAll(async ({ playwright }) => {
    api = await playwright.request.newContext({ baseURL: BACKEND });

    const loginRes = await api.post('/api/v1/login', {
      form: { grant_type: 'password', username: 'foo', password: 'bar' },
    });
    expect(loginRes.ok()).toBeTruthy();
    csrfToken = (await loginRes.json()).csrf_token;

    // Project-scoped read-only entry (stored on the project's internal "dogma" repository).
    await ensureProject(SEED_PROJECT);
    await setRepoStatus(SEED_PROJECT, 'dogma', 'READ_ONLY');

    // A writable repository the form flow will flip to read-only.
    await ensureProject(FORM_PROJECT);
    await ensureRepo(FORM_PROJECT, FORM_REPO);
    await setRepoStatus(FORM_PROJECT, FORM_REPO, 'WRITABLE');

    // The status is applied asynchronously via a repository listener; wait until it is visible.
    await expect
      .poll(async () => (await readOnlyList()).some((e) => e.projectName === SEED_PROJECT), {
        timeout: 10000,
      })
      .toBe(true);
  });

  test.afterAll(async () => {
    // Revert to keep the shared backend clean for other specs.
    if (csrfToken) {
      await setRepoStatus(SEED_PROJECT, 'dogma', 'WRITABLE');
      await setRepoStatus(FORM_PROJECT, FORM_REPO, 'WRITABLE');
    }
    await api?.dispose();
  });

  test('exposes read-only entries with an ISO-8601 updatedAt', async () => {
    const entry = (await readOnlyList()).find((e) => e.projectName === SEED_PROJECT);
    expect(entry).toBeTruthy();
    expect(entry!.status).toBe('READ_ONLY');
    // A project-scoped entry uses "dogma" as its repository name.
    expect(entry!.repoName).toBe('dogma');
    // updatedAt must be an ISO-8601 string, not an epoch number, so the UI renders the real date.
    expect(typeof entry!.updatedAt).toBe('string');
    expect(new Date(entry!.updatedAt!).getFullYear()).toBeGreaterThanOrEqual(2024);
  });

  test.describe('UI', () => {
    test.beforeEach(async ({ page }) => {
      await loginUi(page);
    });

    test('is reachable as a system-administrator settings tab', async ({ page }) => {
      await page.goto('/app/settings/server-status');
      const tab = page.getByRole('tab', { name: 'Repository Status' });
      await expect(tab).toBeVisible();
      await tab.click();
      await expect(page).toHaveURL(/\/app\/settings\/repo-status/);
    });

    test('lists the read-only project with scope, status and a valid date', async ({ page }) => {
      await page.goto('/app/settings/repo-status');

      await expect(page.getByText('Current Server Status:')).toBeVisible();

      const table = page.locator('table');
      await expect(table).toBeVisible();

      // Six columns: Project, Repository, Scope, Status, Updated At, Actions.
      const headers = table.locator('th');
      await expect(headers).toHaveCount(6);
      await expect(headers.nth(0)).toContainText('Project');
      await expect(headers.nth(1)).toContainText('Repository');
      await expect(headers.nth(2)).toContainText('Scope');
      await expect(headers.nth(3)).toContainText('Status');
      await expect(headers.nth(4)).toContainText('Updated At');
      await expect(headers.nth(5)).toContainText('Actions');

      const row = table.locator('tr', { hasText: SEED_PROJECT });
      await expect(row).toContainText('READ_ONLY');
      await expect(row).toContainText('Project'); // project-scoped
      // Regression guard: a numeric epoch updatedAt would render as 1970.
      await expect(row).not.toContainText('1970');
    });

    test('makes a repository read-only through the confirm form', async ({ page }) => {
      // Start from a known-writable state.
      await setRepoStatus(FORM_PROJECT, FORM_REPO, 'WRITABLE');

      await page.goto('/app/settings/repo-status');
      await expect(page.getByText('Make a repository read-only')).toBeVisible();

      await pickOption(page, 'readonly-project-select', FORM_PROJECT);
      await page.waitForTimeout(800); // repositories load after the project is chosen
      await pickOption(page, 'readonly-repo-select', FORM_REPO);

      // Open the confirmation modal.
      await page.getByRole('button', { name: 'Make read-only' }).click();
      const modal = page.locator('.chakra-modal__content');
      await expect(modal).toBeVisible();

      // The confirm button stays disabled until the exact project/repo is retyped.
      const confirm = modal.getByRole('button', { name: 'Make read-only' });
      await expect(confirm).toBeDisabled();
      await page.getByPlaceholder(`${FORM_PROJECT}/${FORM_REPO}`).fill(`${FORM_PROJECT}/${FORM_REPO}`);
      await expect(confirm).toBeEnabled();
      await confirm.click();

      // The repository now appears in the read-only list.
      const row = page.locator('table').locator('tr', { hasText: FORM_REPO });
      await expect(row).toContainText('READ_ONLY', { timeout: 10000 });
      await expect(row).toContainText('Repository');
    });

    test('reverts a read-only repository to writable from the list', async ({ page }) => {
      // Ensure the repository is read-only, then revert it through the list action.
      await setRepoStatus(FORM_PROJECT, FORM_REPO, 'READ_ONLY');
      await expect
        .poll(
          async () =>
            (await readOnlyList()).some((e) => e.projectName === FORM_PROJECT && e.repoName === FORM_REPO),
          { timeout: 10000 },
        )
        .toBe(true);

      await page.goto('/app/settings/repo-status');
      const table = page.locator('table');
      const row = table.locator('tr', { hasText: FORM_REPO });
      await expect(row).toContainText('READ_ONLY');

      await row.getByRole('button', { name: 'Make writable' }).click();
      const modal = page.locator('.chakra-modal__content');
      await expect(modal).toBeVisible();

      // The confirm button stays disabled until the exact project/repo is retyped.
      const confirm = modal.getByRole('button', { name: 'Make writable' });
      await expect(confirm).toBeDisabled();
      await page.getByPlaceholder(`${FORM_PROJECT}/${FORM_REPO}`).fill(`${FORM_PROJECT}/${FORM_REPO}`);
      await expect(confirm).toBeEnabled();
      await confirm.click();

      // The repository leaves the read-only list.
      await expect(table.locator('tr', { hasText: FORM_REPO })).toHaveCount(0, { timeout: 10000 });
    });
  });
});
