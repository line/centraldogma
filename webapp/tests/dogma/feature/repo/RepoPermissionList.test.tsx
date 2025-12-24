import { act, fireEvent, render } from '@testing-library/react';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';
import RepoRoleList, { RepoRoleListProps } from 'dogma/features/repo/RepoRoleList';
import { RepositoryMetadataDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import '@testing-library/jest-dom';

describe('RepoRoleList', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepoRoleListProps<object>;

  beforeEach(() => {
    jest.useFakeTimers();
    const mockRepoRoles = [
      {
        name: 'meta',
        roles: {
          projects: {
            member: 'READ' as RepositoryRole,
            guest: null as 'READ' | 'WRITE' | null,
          },
          users: {},
          appIds: {},
        },
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.128853Z' },
      },
      {
        name: 'repo1',
        roles: {
          projects: {
            member: 'WRITE' as RepositoryRole,
            guest: 'WRITE' as 'READ' | 'WRITE' | null,
          },
          users: {
            'lz123456@localhost.localdomain': 'WRITE',
          },
          appIds: {
            'test-token': 'READ',
          },
        },
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:16:18.853509Z' },
      },
      {
        name: 'repo2',
        roles: {
          projects: {
            member: 'WRITE' as RepositoryRole,
            guest: null as 'READ' | 'WRITE' | null,
          },
          users: {},
          appIds: {},
        },
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:30.973209Z' },
        removal: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:37.020133Z' },
      },
    ];
    expectedProps = {
      data: mockRepoRoles,
      projectName: 'ProjectAlpha',
    };
  });

  it('renders the repo names', () => {
    const { getByText } = render(<RepoRoleList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepositoryMetadataDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { container } = render(<RepoRoleList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(3);
  });

  it('displays a matching repo name', () => {
    const { queryByPlaceholderText, container } = render(<RepoRoleList {...expectedProps} />);
    const inputElement = queryByPlaceholderText(/search.../i);
    fireEvent.change(inputElement!, { target: { value: 'repo1' } });
    act(() => {
      jest.advanceTimersByTime(500);
    });
    const tbody = container.querySelector('tbody');
    expect(tbody.children.length).toBe(1);
    const firstCell = tbody.firstChild.firstChild.firstChild;
    expect(firstCell).toHaveTextContent('repo1');
  });

  it('has `${projectName}/repos/${repoName}/edit` on the repo name', () => {
    const { container } = render(<RepoRoleList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    const firstRepoName = 'meta';
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}/settings`,
    );
  });
});
