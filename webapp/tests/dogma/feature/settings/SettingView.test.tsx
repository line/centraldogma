import { screen } from '@testing-library/react';
import { renderWithProviders } from 'dogma/util/test-utils';
import SettingView from 'dogma/features/settings/SettingView';
import { useGetReplicasQuery } from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetReplicasQuery: jest.fn(),
}));

jest.mock('next/router', () => ({
  useRouter: () => ({
    asPath: '/app/settings/repo-status',
    pathname: '/app/settings/repo-status',
    isReady: true,
    query: {},
    push: jest.fn(),
  }),
}));

const adminPreloadedState = {
  auth: {
    isInAnonymousMode: false,
    csrfToken: null as string | null,
    isLoading: false,
    user: {
      login: 'admin',
      name: 'Admin User',
      email: 'admin@example.com',
      roles: [] as string[],
      systemAdmin: true,
    },
  },
};

const renderSettingView = (replicas: unknown[]) => {
  (useGetReplicasQuery as jest.Mock).mockReturnValue({ data: replicas });
  return renderWithProviders(
    <SettingView currentTab="Repository Status">
      <div>panel</div>
    </SettingView>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    { preloadedState: adminPreloadedState as any },
  );
};

describe('SettingView', () => {
  beforeEach(() => jest.clearAllMocks());

  it('hides the Repository Recovery tab in standalone mode without misaligning the highlight', () => {
    renderSettingView([]);

    expect(screen.queryByRole('tab', { name: 'Repository Recovery' })).toBeNull();
    // The Recovery entry must stay last in TABS: hiding it must not shift which tab is highlighted.
    expect(screen.getByRole('tab', { name: 'Repository Status' })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the Repository Recovery tab in replicated mode', () => {
    renderSettingView([{ serverId: 1, host: '127.0.0.1', current: true }]);

    expect(screen.getByRole('tab', { name: 'Repository Recovery' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Repository Status' })).toHaveAttribute('aria-selected', 'true');
  });
});
