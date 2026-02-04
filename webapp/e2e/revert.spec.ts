import { test, expect, type Page } from '@playwright/test';

const API_BASE = 'http://127.0.0.1:36462';

test.beforeEach('Login', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByText(/Login/)).toBeVisible();
  await page.getByPlaceholder('ID').fill('foo');
  await page.getByPlaceholder('Password').fill('bar');
  await page.getByRole('button', { name: 'Login' }).click();
});

async function apiPost(page: Page, path: string, body: object) {
  const meta = page.locator('meta[name="csrf-token"]');
  await expect(meta).toHaveAttribute('content', /.+/);
  const csrf = await meta.getAttribute('content');
  if (!csrf) {
    throw new Error('Missing CSRF token');
  }
  const result = await page.evaluate(
    async ({ url, csrfToken, payload }) => {
      const res = await fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'X-CSRF-Token': csrfToken,
          'Content-Type': 'application/json; charset=UTF-8',
        },
        body: JSON.stringify(payload),
      });
      return { ok: res.ok, status: res.status, body: await res.text() };
    },
    { url: `${API_BASE}${path}`, csrfToken: csrf, payload: body },
  );
  expect(result.ok).toBeTruthy();
  return result;
}

test('revert commit from history page', async ({ page }) => {
  await apiPost(page, '/api/v1/projects/foo/repos/bar/contents', {
    commitMessage: {
      summary: 'Add foo.json',
      detail: 'Add foo.json',
      markup: 'PLAINTEXT',
    },
    changes: [
      {
        path: '/foo.json',
        type: 'UPSERT_JSON',
        content: { a: 'bar' },
      },
    ],
  });

  await apiPost(page, '/api/v1/projects/foo/repos/bar/contents', {
    commitMessage: {
      summary: 'Add bar.txt',
      detail: 'Add bar.txt',
      markup: 'PLAINTEXT',
    },
    changes: [
      {
        path: '/a/bar.txt',
        type: 'UPSERT_TEXT',
        content: 'text in the file.\n',
      },
    ],
  });

  await apiPost(page, '/api/v1/projects/foo/repos/bar/contents', {
    commitMessage: {
      summary: 'Edit foo.json',
      detail: 'Edit foo.json',
      markup: 'PLAINTEXT',
    },
    changes: [
      {
        path: '/foo.json',
        type: 'UPSERT_JSON',
        content: { a: 'baz' },
      },
    ],
  });

  await page.goto('/app/projects/foo/repos/bar/commits');

  const targetRow = page.locator('tr', { hasText: 'Add foo.json' });
  await expect(targetRow).toBeVisible();
  await targetRow.getByRole('button', { name: 'Revert' }).click();

  const modal = page.getByRole('dialog');
  await expect(modal).toBeVisible();
  await expect(modal.getByPlaceholder('Add a summary')).toBeVisible();
  await modal.getByRole('button', { name: 'Revert' }).click();

  await expect(page.getByText('Repository reverted')).toBeVisible();
});
