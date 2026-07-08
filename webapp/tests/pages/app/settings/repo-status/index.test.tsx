import { renderWithProviders } from 'dogma/util/test-utils';
import RepoStatusPage from 'pages/app/settings/repo-status';
import { RepositoryStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';
import {
  useGetProjectsQuery,
  useGetReadOnlyReposQuery,
  useGetReposQuery,
  useGetServerStatusQuery,
  useUpdateRepositoryStatusMutation,
} from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetReadOnlyReposQuery: jest.fn(),
  useGetServerStatusQuery: jest.fn(),
  useGetProjectsQuery: jest.fn(),
  useGetReposQuery: jest.fn(),
  useUpdateRepositoryStatusMutation: jest.fn(),
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

// Mock chakra-react-select which doesn't work in JSDOM.
jest.mock('chakra-react-select', () => ({
  Select: ({ id, options, onChange, value, placeholder }: any) => (
    <select
      id={id}
      data-testid={id}
      value={value?.value || ''}
      onChange={(e) => onChange(options?.find((o: any) => o.value === e.target.value) || null)}
    >
      <option value="">{placeholder || 'Select...'}</option>
      {options?.map((o: any) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

const mockReadOnlyRepos: RepositoryStatus[] = [
  { projectName: 'foo', repoName: 'bar', status: 'READ_ONLY', updatedAt: '2026-01-01T00:00:00Z' },
  { projectName: 'baz', repoName: 'dogma', status: 'READ_ONLY', updatedAt: '2026-01-02T00:00:00Z' },
];

const baseAuthState = {
  isInAnonymousMode: false,
  csrfToken: null,
  isLoading: false,
  user: {
    login: 'admin',
    name: 'Admin User',
    email: 'admin@example.com',
    roles: [],
    systemAdmin: true,
  },
};

describe('RepoStatusPage', () => {
  beforeEach(() => {
    (useGetServerStatusQuery as jest.Mock).mockReturnValue({
      data: 'WRITABLE',
      error: undefined,
      isLoading: false,
    });
    (useGetProjectsQuery as jest.Mock).mockReturnValue({ data: [], isLoading: false });
    (useGetReposQuery as jest.Mock).mockReturnValue({ data: [], isFetching: false });
    (useUpdateRepositoryStatusMutation as jest.Mock).mockReturnValue([jest.fn(), { isLoading: false }]);
  });

  it('lists read-only projects and repositories', () => {
    (useGetReadOnlyReposQuery as jest.Mock).mockReturnValue({
      data: mockReadOnlyRepos,
      error: undefined,
      isLoading: false,
    });

    const { getByText, getAllByText } = renderWithProviders(<RepoStatusPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(getByText('foo')).toBeInTheDocument();
    expect(getByText('bar')).toBeInTheDocument();
    expect(getByText('baz')).toBeInTheDocument();
    expect(getAllByText('READ_ONLY')).toHaveLength(2);
    // Each read-only entry offers a per-row action to revert it to writable.
    expect(getAllByText('Make writable')).toHaveLength(2);
  });

  it('shows a writable message when there are no read-only repositories', () => {
    (useGetReadOnlyReposQuery as jest.Mock).mockReturnValue({
      data: [],
      error: undefined,
      isLoading: false,
    });

    const { getByText } = renderWithProviders(<RepoStatusPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(getByText('All projects and repositories are writable.')).toBeInTheDocument();
  });

  it('shows the current server status', () => {
    (useGetReadOnlyReposQuery as jest.Mock).mockReturnValue({
      data: [],
      error: undefined,
      isLoading: false,
    });

    const { getByText } = renderWithProviders(<RepoStatusPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(getByText('WRITABLE')).toBeInTheDocument();
  });

  it('renders the make-read-only form', () => {
    (useGetReadOnlyReposQuery as jest.Mock).mockReturnValue({
      data: [],
      error: undefined,
      isLoading: false,
    });

    const { getByText } = renderWithProviders(<RepoStatusPage />, {
      preloadedState: { auth: baseAuthState },
    });

    expect(getByText('Make a repository read-only')).toBeInTheDocument();
    expect(getByText('Make read-only')).toBeInTheDocument();
  });
});
