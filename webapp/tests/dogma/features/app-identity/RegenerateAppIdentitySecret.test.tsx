import '@testing-library/jest-dom';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReactElement } from 'react';
import { renderWithProviders } from 'dogma/util/test-utils';
import { RegenerateAppIdentitySecret } from 'dogma/features/app-identity/RegenerateAppIdentitySecret';
import { apiSlice, useRegenerateAppIdentitySecretMutation } from 'dogma/features/api/apiSlice';
import { setupStore } from 'dogma/store';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useRegenerateAppIdentitySecretMutation: jest.fn(),
}));

// A regenerated token is always deactivated; the server rejects regenerating an active token.
const regeneratedToken = {
  appId: 'app-token-1',
  type: 'TOKEN',
  systemAdmin: false,
  allowGuestAccess: false,
  secret: 'appToken-regenerated-secret',
  creation: { user: 'user@example.com', timestamp: '2024-01-01T00:00:00Z' },
  deactivation: { user: 'user@example.com', timestamp: '2024-01-02T00:00:00Z' },
};

// Renders with a store whose dispatched actions are recorded, so the tests can assert
// when the AppIdentity cache invalidation is dispatched.
function renderWithDispatchSpy(ui: ReactElement) {
  const store = setupStore({});
  const dispatched: unknown[] = [];
  const originalDispatch = store.dispatch;
  store.dispatch = ((action: never) => {
    dispatched.push(action);
    return originalDispatch(action);
  }) as typeof store.dispatch;
  const hasInvalidation = () => dispatched.some((action) => apiSlice.util.invalidateTags.match(action));
  return { hasInvalidation, ...renderWithProviders(ui, { store }) };
}

describe('RegenerateAppIdentitySecret', () => {
  let regenerateSecret: jest.Mock;
  let reset: jest.Mock;

  beforeEach(() => {
    regenerateSecret = jest.fn();
    reset = jest.fn();
    (useRegenerateAppIdentitySecretMutation as jest.Mock).mockReturnValue([
      regenerateSecret,
      { isLoading: false, reset },
    ]);
  });

  it('asks for confirmation before regenerating', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    renderWithProviders(<RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />);

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));

    expect(
      screen.getByText(/the new secret will not work until the app identity is activated/),
    ).toBeInTheDocument();
    expect(regenerateSecret).not.toHaveBeenCalled();
  });

  it('regenerates the secret and shows it once confirmed', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    renderWithProviders(<RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />);

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));

    expect(regenerateSecret).toHaveBeenCalledWith({ appId: 'app-token-1' });
    await waitFor(() => expect(screen.getByText('Secret regenerated')).toBeInTheDocument());
    expect(screen.getByText('appToken-regenerated-secret')).toBeInTheDocument();
  });

  it('warns that an inactive app identity needs activation', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    renderWithProviders(<RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />);

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));

    await waitFor(() => expect(screen.getByText('Secret regenerated')).toBeInTheDocument());
    expect(screen.getByText(/This app identity is inactive/)).toBeInTheDocument();
  });

  it('is disabled for an active token', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    renderWithProviders(<RegenerateAppIdentitySecret appId="app-token-1" hidden={false} disabled />);

    const button = screen.getByRole('button', { name: /regenerate secret/i });
    expect(button).toBeDisabled();

    await userEvent.click(button);
    expect(screen.queryByText(/stays inactive/)).not.toBeInTheDocument();
    expect(regenerateSecret).not.toHaveBeenCalled();
  });

  it('refreshes the app identity list only after the secret modal is closed', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    const { hasInvalidation } = renderWithDispatchSpy(
      <RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));
    await waitFor(() => expect(screen.getByText('Secret regenerated')).toBeInTheDocument());
    // Refreshing while the modal is open would remount the table cell and close the modal.
    expect(hasInvalidation()).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: 'OK' }));

    await waitFor(() => expect(hasInvalidation()).toBe(true));
    expect(reset).toHaveBeenCalled();
  });

  it('refreshes the app identity list when unmounted before the secret modal is closed', async () => {
    regenerateSecret.mockReturnValue({ unwrap: () => Promise.resolve(regeneratedToken) });
    const { hasInvalidation, unmount } = renderWithDispatchSpy(
      <RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />,
    );

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));
    await waitFor(() => expect(screen.getByText('Secret regenerated')).toBeInTheDocument());
    expect(hasInvalidation()).toBe(false);

    unmount();

    expect(hasInvalidation()).toBe(true);
  });

  it('does not show the secret modal when regeneration fails', async () => {
    regenerateSecret.mockReturnValue({
      unwrap: () => Promise.reject({ error: { status: 403, data: 'forbidden' } }),
    });
    renderWithProviders(<RegenerateAppIdentitySecret appId="app-token-1" hidden={false} />);

    await userEvent.click(screen.getByRole('button', { name: /regenerate secret/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate' }));

    expect(regenerateSecret).toHaveBeenCalledWith({ appId: 'app-token-1' });
    await waitFor(() => expect(screen.queryByText('Secret regenerated')).not.toBeInTheDocument());
  });
});
