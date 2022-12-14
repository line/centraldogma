import { render, fireEvent } from '@testing-library/react';
import { HistoryListProps } from 'dogma/features/history/HistoryList';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import HistoryList from 'dogma/features/history/HistoryList';

describe('HistoryList', () => {
  let expectedProps: JSX.IntrinsicAttributes & HistoryListProps<object>;

  beforeEach(() => {
    const mockHistoryList: HistoryDto[] = [
      {
        revision: { major: 8, minor: 0, revisionNumber: '8' },
        author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
        timestamp: '2022-11-30T11:48:57Z',
        summary: 'Edit /zzzzz',
        detail: { content: '', markup: 'PLAINTEXT' },
        diffs: [],
      },
      {
        revision: { major: 7, minor: 0, revisionNumber: '7' },
        author: { name: 'ldap123456', email: 'ldap123456@localhost.localdomain' },
        timestamp: '2022-11-30T10:57:41Z',
        summary: 'Add /test',
        detail: { content: 'zzz', markup: 'PLAINTEXT' },
        diffs: [],
      },
    ];
    expectedProps = {
      data: mockHistoryList,
      projectName: 'ProjectAlpha',
      repoName: 'RepoGamma',
      handleTabChange: jest.fn(),
    };
  });

  it('renders a table with a row for each revision', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    expect(container.querySelector('tbody').children.length).toBe(2);
  });

  it('links the view icon to `${projectName}/repos/${repoName}/list/${revisionNumber} ${summary}`', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    expect(actionCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/list/${8} Edit /zzzzz`,
    );
  });

  it('calls handleTabChange when the view icon is clicked', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const actionCell = container.querySelector('tbody').firstChild.firstChild.lastChild;
    fireEvent.click(actionCell);
    expect(expectedProps.handleTabChange).toHaveBeenCalledTimes(1);
  });

  it('links the revision cell to `${projectName}/repos/${repoName}/list/${revisionNumber} ${summary}`', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/list/${8} Edit /zzzzz`,
    );
  });

  it('calls handleTabChange when the revision cell is clicked', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    fireEvent.click(firstCell);
    expect(expectedProps.handleTabChange).toHaveBeenCalledTimes(1);
  });
});
