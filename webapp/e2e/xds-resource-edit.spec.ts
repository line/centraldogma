import { test, expect } from '@playwright/test';

// Regression test for the xDS resource editor layout: while editing, the commit-summary + Save row lives in a
// sticky action bar so Save stays on screen instead of falling below the 60vh editor.
//
// This exercises the REAL xDS edit UI, which needs a backend with the xDS plugin enabled AND the sample data
// pre-created — i.e. the dedicated xDS test server (`./gradlew :xds:runXdsTestServer`, port 36462, admin/admin,
// which pre-creates the 'my-group' group and 'my-cluster-2' cluster). The default e2e backend
// (`npm run backend` / runTestShiroServer) has no xDS endpoints, so this spec is opt-in: it is skipped unless
// XDS_E2E=1 is set (so it never fails in CI against the xDS-less backend). To run it:
//
//   ./gradlew :xds:runXdsTestServer         # in one shell (leave running)
//   XDS_E2E=1 npm run test:e2e              # in the webapp directory
//
// The pre-created group/cluster ids mirror XdsTestServer.SAMPLE_GROUP / SAMPLE_CLUSTER_2.
const GROUP = 'my-group';
const CLUSTER = 'my-cluster-2';

// A modest viewport reproduces the original bug: the 60vh editor plus the tabs/toolbar/References panel above
// it pushed the Save button below the fold.
test.use({ viewport: { width: 1280, height: 720 } });

test.beforeEach(async ({ page }) => {
  test.skip(!process.env.XDS_E2E, 'Set XDS_E2E=1 and run ./gradlew :xds:runXdsTestServer (see file header).');

  await page.goto('/');
  await expect(page.getByText(/Login/)).toBeVisible();
  await page.getByPlaceholder('ID').fill('admin');
  await page.getByPlaceholder('Password').fill('admin');
  await page.getByRole('button', { name: 'Login' }).click();
});

test('Save button stays in the viewport while editing a cluster', async ({ page }) => {
  await page.goto(`/app/xds/resource?group=${GROUP}&type=clusters&id=${CLUSTER}`);

  // The resource opens read-only; wait for it to load, then switch to edit mode.
  const editButton = page.getByRole('button', { name: /^Edit$/ });
  await expect(editButton).toBeVisible();
  await editButton.click();

  // The sticky action bar keeps both the commit-summary input and Save reachable without scrolling.
  await expect(page.getByPlaceholder(/Update cluster/i)).toBeVisible();
  const saveButton = page.getByRole('button', { name: /^Save$/ });
  await expect(saveButton).toBeVisible();
  await expect(saveButton).toBeInViewport();
});
