import { renderWithProviders } from 'dogma/util/test-utils';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import RepoList, { RepoListProps } from 'dogma/features/repo/RepoList';

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: {},
    pathname: '/app/projects/[projectName]',
    push: jest.fn(),
  }),
}));

describe('RepoList', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepoListProps<object>;

  beforeEach(() => {
    const mockRepos = [
      {
        name: 'meta',
        creator: { name: 'System', email: 'system@localhost.localdomain' },
        headRevision: 1,
        url: '/api/v1/projects/abcd/repos/meta',
        createdAt: '2022-11-23T03:13:49.581Z',
      },
      {
        name: 'repo1',
        creator: { name: 'dummy', email: 'dummy@localhost.localdomain' },
        headRevision: 6,
        url: '/api/v1/projects/abcd/repos/repo1',
        createdAt: '2022-11-23T03:16:17.880Z',
      },
      {
        name: 'repo2',
        creator: { name: 'dummy', email: 'dummy@localhost.localdomain' },
        headRevision: 1,
        url: '/api/v1/projects/abcd/repos/repo2',
        createdAt: '2022-11-28T03:01:47.262Z',
      },
    ];
    expectedProps = {
      data: mockRepos,
      projectName: 'ProjectAlpha',
    };
  });

  it('renders the repo names', () => {
    const { getByText } = renderWithProviders(<RepoList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepoDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { container } = renderWithProviders(<RepoList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(3);
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}/tree/head` on the view icon', () => {
    const { container } = renderWithProviders(<RepoList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    const firstRepoName = 'meta';
    expect(actionCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}/tree/head`,
    );
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}/tree/head` on the file path cell', () => {
    const { container } = renderWithProviders(<RepoList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    const firstRepoName = 'meta';
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}/tree/head`,
    );
  });

  describe('with project metadata', () => {
    // The signed-in user is not a member; repo1 is public, repo2 is private without any grant.
    const metadata = {
      name: 'ProjectAlpha',
      repos: {
        repo1: {
          name: 'repo1',
          roles: { projects: { member: 'WRITE', guest: 'READ' }, users: {}, appIds: {} },
          creation: { user: 'dummy', timestamp: '2022-11-23T03:16:17.880Z' },
        },
        repo2: {
          name: 'repo2',
          roles: { projects: { member: 'WRITE', guest: null }, users: {}, appIds: {} },
          creation: { user: 'dummy', timestamp: '2022-11-28T03:01:47.262Z' },
        },
      },
      members: {},
      appIds: {},
      creation: { user: 'dummy', timestamp: '2022-11-23T03:13:49.581Z' },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any;
    const preloadedState = {
      auth: {
        isInAnonymousMode: false,
        csrfToken: null as string,
        isLoading: false,
        user: {
          login: 'guest',
          name: 'guest',
          email: 'guest@localhost.localdomain',
          roles: [] as string[],
          systemAdmin: false,
        },
      },
    };

    it('marks a public repo with a badge and keeps it accessible', () => {
      const { getByText } = renderWithProviders(<RepoList {...expectedProps} metadata={metadata} />, {
        preloadedState,
      });
      expect(getByText('Public')).toBeVisible();
      expect(getByText('repo1').closest('a')).not.toBeNull();
    });

    it('dims an inaccessible private repo without a link', () => {
      const { getByText } = renderWithProviders(<RepoList {...expectedProps} metadata={metadata} />, {
        preloadedState,
      });
      const repo2 = getByText('repo2');
      expect(repo2.closest('a')).toBeNull();
    });
  });
});
