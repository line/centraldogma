import { render } from '@testing-library/react';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import RepoList, { RepoListProps } from 'dogma/features/repo/RepoList';

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
    const { getByText } = render(<RepoList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepoDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { container } = render(<RepoList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(3);
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}` on the view icon', () => {
    const { container } = render(<RepoList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    const firstRepoName = 'meta';
    expect(actionCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}`,
    );
  });

  it('has `${projectName}/repos/${repoName}/files/head{fileName}` on the file path cell', () => {
    const { container } = render(<RepoList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    const firstRepoName = 'meta';
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}`,
    );
  });
});
