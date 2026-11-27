/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { act, screen, waitFor } from '@testing-library/react';
import { FileDto } from 'dogma/features/file/FileDto';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { renderWithProviders } from 'dogma/util/test-utils';
import CommitViewPage from 'pages/app/projects/[projectName]/repos/[repoName]/commit/[revision]/[[...path]]';

const upstreamA = 'a'.repeat(40);
const upstreamB = 'b'.repeat(40);
let mockRevision: string;
let mockPath: string[] | undefined;
let pendingCommitResponse: Promise<void> | undefined;
let requestedPendingCommit: boolean;
let pendingContentsResponse: Promise<void> | undefined;
let pendingContentsRevision: number;
let pendingContentsPath: string;
let requestedPendingContents: boolean;

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: { projectName: 'project', repoName: 'repo', revision: mockRevision, path: mockPath },
    asPath: `/app/projects/project/repos/repo/commit/${mockRevision}${mockPath ? `/${mockPath.join('/')}` : ''}`,
  }),
}));

// DiffView only needs this mapping; loading the editor would initialise Monaco in JSDOM.
jest.mock('dogma/common/components/editor/FileEditor', () => ({
  extensionToLanguageMap: { json: 'json' },
}));

const commits: HistoryDto[] = [
  {
    revision: 4,
    author: { name: 'Local author', email: 'local@example.com' },
    commitMessage: { summary: 'Local edit C', detail: '', markup: 'PLAINTEXT' },
    pushedAt: '2026-01-03T00:00:00Z',
  },
  {
    revision: 3,
    author: { name: 'Author B', email: 'b@example.com' },
    commitMessage: { summary: 'Add b.json', detail: '', markup: 'PLAINTEXT' },
    pushedAt: '2026-01-02T00:00:00Z',
    upstreamCommitId: upstreamB,
  },
  {
    revision: 2,
    author: { name: 'Author A', email: 'a@example.com' },
    commitMessage: { summary: 'Add a.json', detail: '', markup: 'PLAINTEXT' },
    pushedAt: '2026-01-01T00:00:00Z',
    upstreamCommitId: upstreamA,
  },
];
const changedPaths: Record<number, string> = { 2: '/a.json', 3: '/b.json', 4: '/c.json' };

beforeEach(() => {
  mockRevision = '3';
  mockPath = ['a.json'];
  pendingCommitResponse = undefined;
  requestedPendingCommit = false;
  pendingContentsResponse = undefined;
  requestedPendingContents = false;
  jest.spyOn(global, 'fetch').mockImplementation(async (input) => {
    const request = input instanceof Request ? input : new Request(input);
    const url = new URL(request.url, 'http://localhost');
    let data: unknown;
    if (url.pathname.includes('/commits/')) {
      const from = Number(url.pathname.split('/').pop());
      const to = Number(url.searchParams.get('to') || from);
      const path = url.searchParams.get('path') || '/**';
      const maxCommits = Number(url.searchParams.get('maxCommits') || 100);
      if (from === 4 && to === 4 && path === '/**' && pendingCommitResponse) {
        requestedPendingCommit = true;
        await pendingCommitResponse;
      }
      data = commits
        .filter((commit) => commit.revision <= from && commit.revision >= to)
        .filter((commit) => path === '/**' || changedPaths[commit.revision] === path)
        .slice(0, maxCommits);
      if ((data as HistoryDto[]).length === 0) {
        return new Response(null, { status: 204 });
      }
    } else if (url.pathname.includes('/contents/')) {
      const revision = Number(url.searchParams.get('revision'));
      const path = url.pathname.split('/contents')[1];
      if (revision === pendingContentsRevision && path === pendingContentsPath && pendingContentsResponse) {
        requestedPendingContents = true;
        await pendingContentsResponse;
      }
      data = Object.entries(changedPaths)
        .filter(
          ([changedAt, changedPath]) =>
            Number(changedAt) <= revision && (path === '/**' || changedPath === path),
        )
        .map(
          ([changedAt, changedPath]): FileDto => ({
            path: changedPath,
            revision: Number(changedAt),
            type: 'JSON',
            url: changedPath,
            rawContent: '{"value":1}',
          }),
        );
    } else if (url.pathname.endsWith('/revision/-1')) {
      data = { revision: 4 };
    } else if (url.pathname === '/title') {
      data = { title: 'Central Dogma', hostname: 'localhost' };
    } else {
      throw new Error(`Unexpected request: ${request.url}`);
    }
    return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
  });
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('CommitView metadata', () => {
  it('renders a file addition when the previous history returns no content', async () => {
    mockPath = ['b.json'];
    renderWithProviders(<CommitViewPage />);

    await screen.findByRole('heading', { name: 'Commit 3' });
    expect(screen.getByRole('heading', { name: '/b.json' })).toBeInTheDocument();
    expect(screen.getByText(upstreamB, { exact: true })).toBeInTheDocument();
  });

  it.each([
    {
      transition: 'revision',
      initialRevision: '3',
      initialPath: undefined,
      revision: '4',
      path: undefined,
      contentsRevision: 4,
      contentsPath: '/**',
    },
    {
      transition: 'path',
      initialRevision: '3',
      initialPath: undefined,
      revision: '3',
      path: ['a.json'],
      contentsRevision: 3,
      contentsPath: '/a.json',
    },
    {
      transition: 'previous contents',
      initialRevision: '4',
      initialPath: ['b.json'],
      revision: '4',
      path: undefined,
      contentsRevision: 3,
      contentsPath: '/**',
    },
  ])('waits for current contents after a $transition transition', async (next) => {
    mockRevision = next.initialRevision;
    mockPath = next.initialPath;
    const { rerender, store } = renderWithProviders(<CommitViewPage />);
    await screen.findByRole('heading', { name: `Commit ${next.initialRevision}` });

    let finishResponse: () => void;
    pendingContentsResponse = new Promise<void>((resolve) => {
      finishResponse = resolve;
    });
    pendingContentsRevision = next.contentsRevision;
    pendingContentsPath = next.contentsPath;
    mockRevision = next.revision;
    mockPath = next.path;
    rerender(<CommitViewPage />);
    await waitFor(() => expect(requestedPendingContents).toBe(true));
    await waitFor(() =>
      expect(
        Object.values(store.getState().api.queries).filter((query) => query.status === 'pending'),
      ).toHaveLength(1),
    );
    try {
      await waitFor(() => expect(screen.queryByRole('heading', { name: '/b.json' })).not.toBeInTheDocument());
      expect(screen.queryByRole('heading', { name: `Commit ${next.revision}` })).not.toBeInTheDocument();
    } finally {
      await act(async () => finishResponse());
    }

    await screen.findByRole('heading', { name: `Commit ${next.revision}` });
    if (next.revision === '4') {
      expect(screen.getByRole('heading', { name: '/c.json' })).toBeInTheDocument();
      expect(screen.queryByText('Upstream', { exact: true })).not.toBeInTheDocument();
    } else {
      expect(screen.queryByRole('heading', { name: '/b.json' })).not.toBeInTheDocument();
      expect(screen.getByText(upstreamB, { exact: true })).toBeInTheDocument();
    }
  });

  it('shows the requested commit when the selected file was last changed in an earlier revision', async () => {
    renderWithProviders(<CommitViewPage />);

    await screen.findByRole('heading', { name: 'Commit 3' });
    expect(screen.getByText(upstreamB, { exact: true })).toBeInTheDocument();
    expect(screen.queryByText(upstreamA, { exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Add b.json' })).toBeInTheDocument();
    expect(screen.getByText('Author B')).toBeInTheDocument();
  });

  it('does not display an older upstream SHA for a local commit', async () => {
    mockRevision = '4';
    const { container } = renderWithProviders(<CommitViewPage />);

    await screen.findByRole('heading', { name: 'Commit 4' });
    expect(screen.queryByText('Upstream', { exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Local edit C' })).toBeInTheDocument();
    expect(screen.getByText('Local author')).toBeInTheDocument();
    expect(
      container.querySelector('a[href="/app/projects/project/repos/repo/commit/2/a.json"]'),
    ).toBeInTheDocument();
  });

  it('does not show metadata from the previous page while loading another revision', async () => {
    const { rerender } = renderWithProviders(<CommitViewPage />);
    await screen.findByRole('heading', { name: 'Commit 3' });

    let finishResponse: () => void;
    pendingCommitResponse = new Promise<void>((resolve) => {
      finishResponse = resolve;
    });
    mockRevision = '4';
    rerender(<CommitViewPage />);
    await waitFor(() => expect(requestedPendingCommit).toBe(true));
    try {
      expect(screen.queryByText(upstreamB, { exact: true })).not.toBeInTheDocument();
    } finally {
      await act(async () => finishResponse());
    }

    await screen.findByRole('heading', { name: 'Commit 4' });
    expect(screen.getByRole('heading', { name: 'Local edit C' })).toBeInTheDocument();
    expect(screen.queryByText('Upstream', { exact: true })).not.toBeInTheDocument();
  });

  it.each([{ path: ['b.json'] }, { path: undefined }])(
    'keeps the requested commit for path $path',
    async ({ path }) => {
      mockPath = path;
      renderWithProviders(<CommitViewPage />);

      await screen.findByRole('heading', { name: 'Commit 3' });
      expect(screen.getByText(upstreamB, { exact: true })).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: 'Add b.json' })).toBeInTheDocument();
    },
  );
});
