import { act, fireEvent, render } from '@testing-library/react';
import RepoPermissionList, { RepoPermissionListProps } from 'dogma/features/repo/RepoPermissionList';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import '@testing-library/jest-dom';

describe('RepoPermissionList', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepoPermissionListProps<object>;

  beforeEach(() => {
    jest.useFakeTimers();
    const mockRepoPermissions = [
      {
        name: 'meta',
        perRolePermissions: {
          member: 'READ',
          guest: null as 'READ' | 'WRITE' | null,
        },
        perUserPermissions: {},
        perTokenPermissions: {},
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:13:50.128853Z' },
      },
      {
        name: 'repo1',
        perRolePermissions: { member: 'WRITE', guest: 'WRITE' },
        perUserPermissions: { 'lz123456@localhost.localdomain': 'WRITE' },
        perTokenPermissions: { 'test-token': 'READ' },
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-11-23T03:16:18.853509Z' },
      },
      {
        name: 'repo2',
        perRolePermissions: {
          member: 'WRITE',
          guest: null as 'READ' | 'WRITE' | null,
        },
        perUserPermissions: {},
        perTokenPermissions: {},
        creation: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:30.973209Z' },
        removal: { user: 'lb56789@localhost.localdomain', timestamp: '2022-12-16T05:25:37.020133Z' },
      },
    ];
    expectedProps = {
      data: mockRepoPermissions,
      projectName: 'ProjectAlpha',
    };
  });

  it('renders the repo names', () => {
    const { getByText } = render(<RepoPermissionList {...expectedProps} />);
    let name;
    expectedProps.data.forEach((repo: RepoPermissionDetailDto) => {
      name = getByText(repo.name);
      expect(name).toBeVisible();
    });
  });

  it('renders a table with a row for each repo', () => {
    const { container } = render(<RepoPermissionList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(3);
  });

  it('displays a matching repo name', () => {
    const { queryByPlaceholderText, container } = render(<RepoPermissionList {...expectedProps} />);
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
    const { container } = render(<RepoPermissionList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    const firstRepoName = 'meta';
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${firstRepoName}/permissions`,
    );
  });
});
