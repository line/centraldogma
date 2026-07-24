import '@testing-library/jest-dom';
import { renderWithProviders } from 'dogma/util/test-utils';
import AppIdentityPage from 'pages/app/settings/app-identities';
import { AppIdentityDto } from 'dogma/features/app-identity/AppIdentity';
import { useGetAppIdentitiesQuery } from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetAppIdentitiesQuery: jest.fn(),
}));

jest.mock('next/router', () => ({
  useRouter: () => ({ asPath: '/app/settings/app-identities', isReady: true, query: {} }),
}));

const mockIdentities: AppIdentityDto[] = [
  {
    appId: 'app-token-1',
    type: 'TOKEN',
    systemAdmin: false,
    allowGuestAccess: false,
    creation: { user: 'user@example.com', timestamp: '2024-01-01T00:00:00Z' },
  },
  {
    appId: 'app-admin-1',
    type: 'TOKEN',
    systemAdmin: true,
    allowGuestAccess: false,
    creation: { user: 'admin@example.com', timestamp: '2024-01-02T00:00:00Z' },
  },
];

const baseAuthState = {
  isInAnonymousMode: false,
  csrfToken: null,
  isLoading: false,
  user: {
    login: 'user',
    name: 'Test User',
    email: 'user@example.com',
    roles: [],
    systemAdmin: false,
  },
};

describe('AppIdentityPage', () => {
  beforeEach(() => {
    (useGetAppIdentitiesQuery as jest.Mock).mockReturnValue({
      data: mockIdentities,
      error: undefined,
      isLoading: false,
    });
  });

  it('hides the Level column for non-system-admin users', () => {
    const { queryByText } = renderWithProviders(<AppIdentityPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(queryByText('Level')).not.toBeInTheDocument();
  });

  it('shows the Level column for system-admin users', () => {
    const { getByText } = renderWithProviders(<AppIdentityPage />, {
      preloadedState: {
        auth: { ...baseAuthState, user: { ...baseAuthState.user, systemAdmin: true } },
      },
    });

    expect(getByText('Level')).toBeInTheDocument();
  });

  it('renders System Admin and User badges in the Level column for system-admin users', () => {
    const { getByText } = renderWithProviders(<AppIdentityPage />, {
      preloadedState: {
        auth: { ...baseAuthState, user: { ...baseAuthState.user, systemAdmin: true } },
      },
    });

    expect(getByText('System Admin')).toBeInTheDocument();
    expect(getByText('User')).toBeInTheDocument();
  });

  it('displays app identities in the table', () => {
    const { getByText } = renderWithProviders(<AppIdentityPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(getByText('app-token-1')).toBeInTheDocument();
    expect(getByText('app-admin-1')).toBeInTheDocument();
  });

  it('offers the regenerate secret action only for live tokens', () => {
    (useGetAppIdentitiesQuery as jest.Mock).mockReturnValue({
      data: [
        mockIdentities[0],
        {
          appId: 'app-cert-1',
          type: 'CERTIFICATE',
          certificateId: 'cert/1',
          systemAdmin: false,
          allowGuestAccess: false,
          creation: { user: 'user@example.com', timestamp: '2024-01-03T00:00:00Z' },
        },
        {
          appId: 'app-deleted-1',
          type: 'TOKEN',
          systemAdmin: false,
          allowGuestAccess: false,
          creation: { user: 'user@example.com', timestamp: '2024-01-04T00:00:00Z' },
          deactivation: { user: 'user@example.com', timestamp: '2024-01-05T00:00:00Z' },
          deletion: { user: 'user@example.com', timestamp: '2024-01-05T00:00:00Z' },
        },
      ] as AppIdentityDto[],
      error: undefined,
      isLoading: false,
    });
    const { getAllByRole } = renderWithProviders(<AppIdentityPage />, {
      preloadedState: { auth: baseAuthState },
    });

    // Hidden buttons are excluded from the accessibility tree, so only the live token row's button
    // is found; the certificate row and the token scheduled for deletion offer no regenerate action.
    expect(getAllByRole('button', { name: /regenerate secret/i })).toHaveLength(1);
  });
});
