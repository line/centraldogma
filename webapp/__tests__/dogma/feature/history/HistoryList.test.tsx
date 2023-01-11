import { render, fireEvent } from '@testing-library/react';
import { HistoryListProps } from 'dogma/features/history/HistoryList';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import HistoryList from 'dogma/features/history/HistoryList';

describe('HistoryList', () => {
  let expectedProps: JSX.IntrinsicAttributes & HistoryListProps<object>;

  beforeEach(() => {
    const mockHistoryList: HistoryDto[] = [
      {
        revision: 2,
        author: { name: 'System', email: 'system@localhost.localdomain' },
        commitMessage: { summary: 'Update repository', detail: '', markup: 'PLAINTEXT' },
        pushedAt: '2023-01-11T08:17:22Z',
      },
      {
        revision: 1,
        author: { name: 'System', email: 'system@localhost.localdomain' },
        commitMessage: { summary: 'Create a new repository', detail: '', markup: 'PLAINTEXT' },
        pushedAt: '2023-01-10T08:17:22Z',
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

  it('links the revision cell to `${projectName}/repos/${repoName}/list/${revisionNumber}`', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    expect(firstCell).toHaveAttribute(
      'href',
      `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/list/${2}`,
    );
  });

  it('calls handleTabChange when the revision cell is clicked', () => {
    const { container } = render(<HistoryList {...expectedProps} />);
    const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
    fireEvent.click(firstCell);
    expect(expectedProps.handleTabChange).toHaveBeenCalledTimes(1);
  });
});
