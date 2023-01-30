import { render, waitFor, fireEvent } from '@testing-library/react';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import HistoryList from 'dogma/features/history/HistoryList';
import { setupServer } from 'msw/node';
import { rest } from 'msw';
import { ApiProvider } from '@reduxjs/toolkit/dist/query/react';
import { apiSlice } from 'dogma/features/api/apiSlice';

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

const handlers = [
  rest.get(
    `http://localhost/api/v1/projects/${expectedProps.projectName}/repos/${expectedProps.repoName}/commits/-1`,
    (_, res, ctx) => {
      return res(ctx.status(200), ctx.json<HistoryDto[]>(mockHistoryList));
    },
  ),
];

describe('HistoryList', () => {
  const server = setupServer(...handlers);
  beforeAll(() => {
    server.listen({ onUnhandledRequest: 'error' });
  });

  afterEach(() => {
    server.resetHandlers();
  });

  afterAll(() => {
    server.close();
  });

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
