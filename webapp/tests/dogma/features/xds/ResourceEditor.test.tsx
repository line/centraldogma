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
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import { ResourceEditor } from 'dogma/features/xds/ResourceEditor';
import * as xdsApiSlice from 'dogma/features/xds/xdsApiSlice';

jest.mock('next/router', () => ({
  __esModule: true,
  default: { push: jest.fn() },
}));

jest.mock('dogma/features/xds/useGroupWriteAccess', () => ({
  useGroupWriteAccess: () => ({ hasWrite: true, isLoading: false }),
}));

// Monaco cannot mount in JSDOM; replace the YAML editor with a plain textarea.
jest.mock('dogma/common/components/JsonEditor', () => ({
  JsonEditor: ({ value, onChange, readOnly }: any) => (
    <textarea
      aria-label="editor"
      readOnly={readOnly}
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
    />
  ),
}));

// The graph and history panels make unrelated API calls; stub them out.
jest.mock('dogma/features/xds/ResourceGraph', () => ({
  ResourceGraph: () => null,
}));
jest.mock('dogma/features/xds/ResourceHistory', () => ({
  ResourceHistory: () => null,
}));

jest.mock('dogma/features/xds/xdsApiSlice', () => ({
  // Preserve reducerPath and reducer so the Redux store initialises correctly.
  ...jest.requireActual('dogma/features/xds/xdsApiSlice'),
  useGetResourceQuery: jest.fn(),
  useCreateResourceMutation: jest.fn(),
  useUpdateResourceMutation: jest.fn(),
  useDeleteResourceMutation: jest.fn(),
}));

// A cluster (CDS) definition referencing an EDS endpoint, matching the shape the resource query returns.
const CLUSTER_CONTENT = ['name: groups/foo/clusters/my-cluster', 'type: EDS', 'connectTimeout: 5s'].join('\n');

describe('ResourceEditor – sticky action bar', () => {
  let mockUpdate: jest.Mock;

  beforeEach(() => {
    mockUpdate = jest.fn().mockReturnValue({ unwrap: () => Promise.resolve({}) });

    jest.mocked(xdsApiSlice.useGetResourceQuery).mockReturnValue({
      data: { content: CLUSTER_CONTENT, path: '/clusters/my-cluster.yaml' },
      isLoading: false,
      error: undefined,
    } as any);
    jest
      .mocked(xdsApiSlice.useCreateResourceMutation)
      .mockReturnValue([
        jest.fn().mockReturnValue({ unwrap: () => Promise.resolve({}) }),
        { isLoading: false },
      ] as any);
    jest
      .mocked(xdsApiSlice.useUpdateResourceMutation)
      .mockReturnValue([mockUpdate, { isLoading: false }] as any);
    jest
      .mocked(xdsApiSlice.useDeleteResourceMutation)
      .mockReturnValue([jest.fn(), { isLoading: false }] as any);
  });

  describe('existing resource', () => {
    it('reveals the commit input, Save and Cancel only after clicking Edit', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResourceEditor group="foo" type="clusters" id="my-cluster" isNew={false} />);

      // Read-only view: Edit is offered; the editing action bar is not rendered yet.
      expect(await screen.findByRole('button', { name: /^edit$/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
      expect(screen.queryByPlaceholderText(/Update cluster/i)).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /^edit$/i }));

      // Editing: the sticky bar shows the commit summary input plus Cancel and Save; Edit is hidden.
      expect(screen.getByPlaceholderText(/Update cluster/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^save$/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^cancel$/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
    });

    it('returns to read-only view when Cancel is clicked', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResourceEditor group="foo" type="clusters" id="my-cluster" isNew={false} />);

      await user.click(await screen.findByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^cancel$/i }));

      expect(await screen.findByRole('button', { name: /^edit$/i })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
      expect(screen.queryByPlaceholderText(/Update cluster/i)).not.toBeInTheDocument();
    });

    it('calls updateResource when Save is clicked', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResourceEditor group="foo" type="clusters" id="my-cluster" isNew={false} />);

      await user.click(await screen.findByRole('button', { name: /^edit$/i }));
      await user.click(screen.getByRole('button', { name: /^save$/i }));

      await waitFor(() => {
        expect(mockUpdate).toHaveBeenCalled();
      });
    });
  });

  describe('new resource', () => {
    it('shows the Create button and commit input in the action bar', async () => {
      renderWithProviders(<ResourceEditor group="foo" type="clusters" isNew />);

      expect(await screen.findByPlaceholderText(/Enter Cluster ID/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^create$/i })).toBeInTheDocument();
      expect(screen.getByPlaceholderText(/Create cluster/i)).toBeInTheDocument();
    });
  });
});
