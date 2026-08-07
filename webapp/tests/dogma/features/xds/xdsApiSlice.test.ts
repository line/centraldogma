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
// The component tests mock the hooks, so this drives the real endpoint with a stubbed fetch. The revision
// parameter name has to match the server's @Param("revision"), or a stale save silently applies.
import 'whatwg-fetch';
import { setupStore } from 'dogma/store';
import { xdsApiSlice } from 'dogma/features/xds/xdsApiSlice';

describe('xdsApiSlice – updateK8sAggregator', () => {
  afterEach(() => jest.restoreAllMocks());

  it('sends a PUT carrying the summary and the loaded revision', async () => {
    const fetchSpy = jest
      .spyOn(window, 'fetch')
      .mockResolvedValue(
        new Response('stored: yaml\n', { status: 200, headers: { 'Content-Type': 'application/yaml' } }),
      );

    await setupStore().dispatch(
      xdsApiSlice.endpoints.updateK8sAggregator.initiate({
        group: 'foo',
        id: 'my-agg',
        body: 'a: b\n',
        summary: 'update & verify',
        revision: '7',
      }),
    );

    const request = fetchSpy.mock.calls[0][0] as Request;
    expect(request.method).toBe('PUT');
    const url = new URL(request.url, 'http://localhost');
    expect(url.pathname).toBe('/api/v1/xds/groups/foo/k8s/endpointAggregators/my-agg');
    expect(url.searchParams.get('summary')).toBe('update & verify');
    expect(url.searchParams.get('revision')).toBe('7');
  });
});
