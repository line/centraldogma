import { act, fireEvent, render } from '@testing-library/react';
import RepoMemberList, { RepoMemberListProps } from 'dogma/features/repo/RepoMemberList';
import { RepoMemberDetailDto } from 'dogma/features/repo/RepoMemberDto';
import { formatDistance } from 'date-fns';

describe('RepoMemberList', () => {
  let expectedProps: JSX.IntrinsicAttributes & RepoMemberListProps<object>;

  beforeEach(() => {
    jest.useFakeTimers();
    const mockRepoMembers = [
      {
        login: 'lz123456@localhost.localdomain',
        role: 'OWNER',
        creation: { user: 'lz123456@localhost.localdomain', timestamp: '2022-11-30T02:43:04.655753Z' },
      },
      {
        login: 'lb56789@localhost.localdomain',
        role: 'MEMBER',
        creation: { user: 'lz123456@localhost.localdomain', timestamp: '2022-12-16T02:54:12.431395Z' },
      },
    ];
    expectedProps = {
      data: mockRepoMembers,
    };
  });

  it('renders the login ID', () => {
    const { container } = render(<RepoMemberList {...expectedProps} />);
    const tbody = container.querySelector('tbody');
    expect(tbody.children.length).toBe(2);
    const firstCell = tbody.firstChild.firstChild.firstChild;
    expect(firstCell).toHaveTextContent('lz123456@localhost.localdomain');
    const secondCell = tbody.lastChild.firstChild.firstChild;
    expect(secondCell).toHaveTextContent('lb56789@localhost.localdomain');
  });

  it('renders the role', () => {
    const { getByText } = render(<RepoMemberList {...expectedProps} />);
    let role;
    expectedProps.data.forEach((repo: RepoMemberDetailDto) => {
      role = getByText(repo.role);
      expect(role).toBeVisible();
    });
  });

  it('renders the creator (added by)', () => {
    const { container } = render(<RepoMemberList {...expectedProps} />);
    const tbody = container.querySelector('tbody');
    const firstCreator = tbody.children[0].children[2].firstChild;
    expect(firstCreator).toHaveTextContent('lz123456@localhost.localdomain');
  });

  it('renders the timestamp (added at)', () => {
    const { container } = render(<RepoMemberList {...expectedProps} />);
    const tbody = container.querySelector('tbody');
    const timestamp = tbody.children[0].children[3].firstChild;
    expect(timestamp).toHaveTextContent(
      formatDistance(new Date('2022-11-30T02:43:04.655753Z'), new Date(), { addSuffix: true }),
    );
  });

  it('renders the delete button', () => {
    const { container } = render(<RepoMemberList {...expectedProps} />);
    const tbody = container.querySelector('tbody');
    const firstCreator = tbody.children[0].children[4].firstChild;
    expect(firstCreator).toHaveTextContent('Delete');
  });

  it('renders a table with a row for each member', () => {
    const { container } = render(<RepoMemberList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(2);
  });

  it('displays a matching login ID when searched', () => {
    const { queryByPlaceholderText, container } = render(<RepoMemberList {...expectedProps} />);
    const inputElement = queryByPlaceholderText(/search.../i);
    fireEvent.change(inputElement!, { target: { value: 'lz123' } });
    act(() => {
      jest.advanceTimersByTime(500);
    });
    const tbody = container.querySelector('tbody');
    expect(tbody.children.length).toBe(1);
    const firstCell = tbody.firstChild.firstChild.firstChild;
    expect(firstCell).toHaveTextContent('lz123456@localhost.localdomain');
  });
});
