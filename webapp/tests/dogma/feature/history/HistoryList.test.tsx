import { render, screen, waitFor, within } from '@testing-library/react';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import HistoryList from 'dogma/features/history/HistoryList';
// Disabled to due to https://github.com/mswjs/msw/issues/1786
// import { setupServer } from 'msw/node';
// import { http, HttpResponse } from 'msw';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { ApiProvider } from '@reduxjs/toolkit/query/react';
import { renderWithProviders } from 'dogma/util/test-utils';
const mockHistoryList: HistoryDto[] = [
  {
    revision: 2,
    author: { name: 'System', email: 'system@localhost.localdomain' },
    commitMessage: { summary: 'Update repository', detail: '', markup: 'PLAINTEXT' },
    pushedAt: '2023-01-11T08:17:22Z',
    commitId: '0123456789abcdef0123456789abcdef01234567',
    upstreamCommitId: '89abcdef0123456789abcdef0123456789abcdef',
  },
  {
    revision: 1,
    author: { name: 'System', email: 'system@localhost.localdomain' },
    commitMessage: { summary: 'Create a new repository', detail: '', markup: 'PLAINTEXT' },
    pushedAt: '2023-01-10T08:17:22Z',
  },
];
const expectedProps = {
  projectName: 'ProjectAlpha',
  repoName: 'RepoGamma',
  filePath: '',
  headRevision: 2,
  data: mockHistoryList,
  pagination: { pageIndex: 0, pageSize: 10 },
  setPagination: jest.fn(),
  pageCount: 1,
  isDirectory: false,
};

describe('HistoryList commit IDs', () => {
  it('renders both commit IDs in the revision cell instead of a separate column', () => {
    renderWithProviders(<HistoryList {...expectedProps} filePath="/config.yaml" />);

    const firstRow = screen.getAllByTestId('table-row')[0];
    const revisionCell = within(firstRow).getAllByRole('cell')[0];
    expect(within(revisionCell).getByText('Central Dogma')).toBeInTheDocument();
    expect(within(revisionCell).getByText('Upstream')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Commit' })).not.toBeInTheDocument();
  });
});

// const handlers = [
//   http.get(
//     `/api/v1/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/commits/-1`,
//     () => {
//       return HttpResponse.json(mockHistoryList);
//     },
//   ),
// ];

// TODO(ikhoon): Revive this tests

xdescribe('HistoryList', () => {
  // const server = setupServer(...handlers);
  // beforeAll(() => {
  //   server.listen({ onUnhandledRequest: 'error' });
  // });
  //
  // afterEach(() => {
  //   server.resetHandlers();
  // });
  //
  // afterAll(() => {
  //   server.close();
  // });

  it('renders a table with a row for each revision', async () => {
    const { container } = render(
      <ApiProvider api={apiSlice}>
        <HistoryList {...expectedProps} />
      </ApiProvider>,
    );
    await waitFor(() => {
      expect(container.querySelector('tbody').children.length).toBe(2);
    });
  });

  it('links the revision cell to `${projectName}/repos/${repoName}/tree/${revisionNumber}`', async () => {
    const { container } = render(
      <ApiProvider api={apiSlice}>
        <HistoryList {...expectedProps} />
      </ApiProvider>,
    );
    await waitFor(() => {
      const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
      expect(firstCell).toHaveAttribute(
        'href',
        `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/tree/${2}`,
      );
    });
  });
});
