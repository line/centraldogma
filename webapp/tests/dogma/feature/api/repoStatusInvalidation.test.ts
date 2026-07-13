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

import { waitFor } from '@testing-library/react';
import { apiSlice } from 'dogma/features/api/apiSlice';
import { setupStore } from 'dogma/store';

const json = (body: unknown) =>
  Promise.resolve(
    new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
  );

const requestedUrls: string[] = [];

const fetchMock = jest.fn((input: RequestInfo | URL) => {
  const request = input as Request;
  const url = typeof input === 'string' ? input : request.url;
  requestedUrls.push(url);
  if (request.method === 'PUT') {
    return json({});
  }
  if (url.endsWith('/repos')) {
    return json([
      {
        name: 'bar',
        creator: { name: 'System', email: 'system@localhost.localdomain' },
        headRevision: 1,
        url: '/api/v1/projects/foo/repos/bar',
        createdAt: '2026-01-01T00:00:00Z',
        status: 'WRITABLE',
      },
    ]);
  }
  return json([{ name: 'foo', url: '/api/v1/projects/foo', status: 'WRITABLE' }]);
});

const countOf = (suffix: string) => requestedUrls.filter((url) => url.endsWith(suffix)).length;

// The project and repository lists carry the replication status, so a status change has to refetch them.
// Otherwise the read-only badge and the disabled write buttons keep showing the previous status until the
// cache entry is dropped.
describe('updateRepositoryStatus cache invalidation', () => {
  beforeEach(() => {
    requestedUrls.length = 0;
    fetchMock.mockClear();
    global.fetch = fetchMock as unknown as typeof fetch;
  });

  it('refetches the project and repository lists after a status change', async () => {
    const store = setupStore();

    // Both lists stay subscribed, as they are while the pages are mounted.
    store.dispatch(apiSlice.endpoints.getProjects.initiate({ systemAdmin: false }));
    store.dispatch(apiSlice.endpoints.getRepos.initiate('foo'));
    await waitFor(() => {
      expect(countOf('/api/v1/projects')).toBe(1);
      expect(countOf('/repos')).toBe(1);
    });

    await store.dispatch(
      apiSlice.endpoints.updateRepositoryStatus.initiate({
        projectName: 'foo',
        repoName: 'bar',
        status: 'READ_ONLY',
      }),
    );

    await waitFor(() => {
      expect(countOf('/api/v1/projects')).toBe(2);
      expect(countOf('/repos')).toBe(2);
    });
  });

  it('refetches the read-only repository list after a status change', async () => {
    const store = setupStore();

    store.dispatch(apiSlice.endpoints.getReadOnlyRepos.initiate());
    await waitFor(() => expect(countOf('/api/v1/status/repos/read-only')).toBe(1));

    await store.dispatch(
      apiSlice.endpoints.updateRepositoryStatus.initiate({
        projectName: 'foo',
        repoName: 'bar',
        status: 'READ_ONLY',
      }),
    );

    await waitFor(() => expect(countOf('/api/v1/status/repos/read-only')).toBe(2));
  });
});
