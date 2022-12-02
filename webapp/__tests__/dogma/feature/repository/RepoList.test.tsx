import { fireEvent, render } from '@testing-library/react';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import RepoList, { RepoListProps } from 'dogma/features/repo/RepoList';

const useRouter = jest.spyOn(require('next/router'), 'useRouter');

describe('RepoList', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepoListProps<object>;
  let pathName = '';

  beforeEach(() => {
    useRouter.mockImplementationOnce(() => {
      return {
        push: async (newPathname: string) => {
          pathName = newPathname;
        },
      };
    });
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
      name: 'ProjectAlpha',
    };
  });

  it('renders the repository names', () => {
    const { getByText } = render(<RepoList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepoDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { getByTestId } = render(<RepoList {...expectedProps} />);
    expect(getByTestId('table-body').children.length).toBe(3);
  });

  it('does not generates `${projectName}/repos/${repositoryName}` url when the table row is clicked', () => {
    const { getByTestId } = render(<RepoList {...expectedProps} />);
    const row = getByTestId('table-body').children[0];
    fireEvent.click(row);
    expect(pathName).toEqual('');
  });

  it('generates `${projectName}/repos/${repositoryName}` url when the view icon is clicked', () => {
    const { getByTestId } = render(<RepoList {...expectedProps} />);
    const repoName = 'repo1';
    const repoViewLink = getByTestId('ProjectAlpha/repos-repo1');
    expect(repoViewLink).toHaveAttribute('href', `ProjectAlpha/repos/${repoName}`);
  });
});
