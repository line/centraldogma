import { fireEvent, render, waitFor } from '@testing-library/react';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import HistoryList from 'dogma/features/history/HistoryList';
// Disabled to due to https://github.com/mswjs/msw/issues/1786
// import { setupServer } from 'msw/node';
// import { http, HttpResponse } from 'msw';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { ApiProvider } from "@reduxjs/toolkit/query/react";
const expectedProps = {
  projectName: 'ProjectAlpha',
  repoName: 'RepoGamma',
  handleTabChange: jest.fn(),
  totalRevision: 2,
};
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

  it('links the revision cell to `${projectName}/repos/${repoName}/list/${revisionNumber}`', async () => {
    const { container } = render(
      <ApiProvider api={apiSlice}>
        <HistoryList {...expectedProps} />
      </ApiProvider>,
    );
    await waitFor(() => {
      const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
      expect(firstCell).toHaveAttribute(
        'href',
        `/app/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/list/${2}`,
      );
    });
  });

  it('calls handleTabChange when the revision cell is clicked', async () => {
    const { container } = render(
      <ApiProvider api={apiSlice}>
        <HistoryList {...expectedProps} />
      </ApiProvider>,
    );
    await waitFor(() => {
      const firstCell = container.querySelector('tbody').firstChild.firstChild.firstChild;
      fireEvent.click(firstCell);
      expect(expectedProps.handleTabChange).toHaveBeenCalledTimes(1);
    });
  });
});
