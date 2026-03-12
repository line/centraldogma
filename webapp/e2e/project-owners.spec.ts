import { test, expect } from '@playwright/test';

test.beforeEach('Login', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByText(/Login/)).toBeVisible();
  await page.getByPlaceholder('ID').fill('foo');
  await page.getByPlaceholder('Password').fill('bar');
  const loginResponsePromise = page.waitForResponse((response) => response.url().includes('/api/v1/login'));
  await page.getByRole('button', { name: 'Login' }).click();
  await loginResponsePromise;
});

test('view project members from list', async ({ page }) => {
  await page.goto('/app/projects');

  const projectRows = page.locator('tr', { has: page.getByRole('button', { name: 'View members' }) });
  await expect(projectRows.first()).toBeVisible();
  const rowCount = await projectRows.count();
  let projectName = '';
  let members: string[] = [];
  let targetRowIndex = 0;
  for (let i = 0; i < rowCount; i += 1) {
    const row = projectRows.nth(i);
    const candidateName = (await row.getByRole('link').first().innerText()).trim();
    const metadataResponse = await page.request.get(`/api/v1/projects/${encodeURIComponent(candidateName)}`);
    if (!metadataResponse.ok()) {
      continue;
    }
    const metadata = await metadataResponse.json();
    const candidateMembers = Object.entries(metadata.members).map(
      ([login, member]: [string, { login?: string }]) => member.login || login,
    );
    if (candidateMembers.length > 0) {
      projectName = candidateName;
      members = candidateMembers;
      targetRowIndex = i;
      break;
    }
  }
  if (!projectName) {
    const fallbackRow = projectRows.first();
    projectName = (await fallbackRow.getByRole('link').first().innerText()).trim();
  }

  const projectRow = projectRows.nth(targetRowIndex);
  await projectRow.getByRole('button', { name: 'View members' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('Project members')).toBeVisible();
  if (members.length > 0) {
    await expect(dialog.getByTestId('project-member-login').first()).toBeVisible({ timeout: 15000 });
  }
});
