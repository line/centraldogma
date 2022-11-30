import { fireEvent, render } from '@testing-library/react';
import RepositoryList, { RepositoryListProps } from 'dogma/features/repository/RepositoryList';
import { mockRepos } from 'pages/api/v1/projects/abcd';
import { RepoDto } from 'dogma/features/repository/RepoDto';

const useRouter = jest.spyOn(require('next/router'), 'useRouter');

describe('Repository List', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepositoryListProps<object>;
  let pathName = '';

  beforeEach(() => {
    useRouter.mockImplementationOnce(() => {
      return {
        push: async (newPathname: string) => {
          pathName = newPathname;
        },
      };
    });
    expectedProps = {
      data: mockRepos,
      name: 'ProjectAlpha',
    };
  });

  it('renders the repository names', () => {
    const { getByText } = render(<RepositoryList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepoDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { getByTestId } = render(<RepositoryList {...expectedProps} />);
    expect(getByTestId('table-body').children.length).toBe(mockRepos.length);
  });

  it('generates `${projectName}/repos/${repositoryName}` url when the table row is clicked', () => {
    const { getByTestId } = render(<RepositoryList {...expectedProps} />);
    const row = getByTestId('table-body').children[0];
    fireEvent.click(row);
    expect(pathName).toEqual(`ProjectAlpha/repos/${mockRepos[0].name}`);
  });
});
