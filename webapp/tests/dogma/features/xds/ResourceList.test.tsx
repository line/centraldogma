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
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import { ResourceList } from 'dogma/features/xds/ResourceList';
import { XdsResourceDto } from 'dogma/features/xds/XdsTypes';
import * as xdsApiSlice from 'dogma/features/xds/xdsApiSlice';

const mockPush = jest.fn();
let mockQuery: Record<string, string | string[]> = {};

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: mockQuery,
    pathname: '/app/xds/group',
    push: mockPush,
  }),
}));

// Read-only view so the list has no action column or modal, keeping the pagination behaviour isolated.
jest.mock('dogma/features/xds/useGroupWriteAccess', () => ({
  useGroupWriteAccess: () => ({ hasWrite: false, isLoading: false }),
}));

jest.mock('dogma/features/xds/xdsApiSlice', () => ({
  // Preserve reducerPath/reducer so the Redux store initialises correctly.
  ...jest.requireActual('dogma/features/xds/xdsApiSlice'),
  useListResourcesQuery: jest.fn(),
  useDeleteResourceMutation: jest.fn(),
}));

function makeResources(n: number): XdsResourceDto[] {
  return Array.from({ length: n }, (_, i) => {
    const id = `c${String(i + 1).padStart(2, '0')}`;
    return { id, path: `/clusters/${id}.yaml`, revision: 1 };
  });
}

describe('ResourceList – URL-persisted pagination', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockQuery = {};
    jest.mocked(xdsApiSlice.useListResourcesQuery).mockReturnValue({
      data: makeResources(25),
      isLoading: false,
      error: undefined,
    } as any);
    jest
      .mocked(xdsApiSlice.useDeleteResourceMutation)
      .mockReturnValue([jest.fn(), { isLoading: false }] as any);
  });

  it('restores the page from the ?page query param (back-navigation fix)', () => {
    mockQuery = { name: 'g', type: 'clusters', page: '2' };
    renderWithProviders(<ResourceList group="g" type="clusters" />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(10);
    expect(screen.getByText('groups/g/clusters/c11')).toBeInTheDocument();
    expect(screen.queryByText('groups/g/clusters/c01')).not.toBeInTheDocument();
  });

  it('writes the next page to the URL while preserving the group and section params', async () => {
    const user = userEvent.setup();
    mockQuery = { name: 'g', type: 'clusters' };
    renderWithProviders(<ResourceList group="g" type="clusters" />);

    await user.click(screen.getByRole('button', { name: /next/i }));

    expect(mockPush).toHaveBeenCalledWith(
      { pathname: '/app/xds/group', query: { name: 'g', type: 'clusters', page: '2' } },
      undefined,
      { shallow: true },
    );
  });

  it('restores the page size from the ?pageSize query param', () => {
    mockQuery = { name: 'g', type: 'clusters', pageSize: '20' };
    renderWithProviders(<ResourceList group="g" type="clusters" />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(20);
  });

  it('writes the selected page size to the URL', async () => {
    const user = userEvent.setup();
    mockQuery = { name: 'g', type: 'clusters' };
    renderWithProviders(<ResourceList group="g" type="clusters" />);

    await user.selectOptions(screen.getByRole('combobox'), '20');

    expect(mockPush).toHaveBeenCalledWith(
      { pathname: '/app/xds/group', query: { name: 'g', type: 'clusters', pageSize: '20' } },
      undefined,
      { shallow: true },
    );
  });

  // Regression: with autoResetPageIndex disabled, a deletion that shrinks the list below the current page
  // must not strand the user on a blank page — useClampPageIndex clamps back to the last valid page.
  it('clamps to the last page when a deletion shrinks the list below the current page', () => {
    mockQuery = { name: 'g', type: 'clusters', page: '3' };
    const { rerender } = renderWithProviders(<ResourceList group="g" type="clusters" />);
    // Page 3 of 3 is valid for 25 resources.
    expect(screen.getByText('groups/g/clusters/c21')).toBeInTheDocument();

    // A deletion refetches the same mounted list with far fewer rows (now a single page).
    jest.mocked(xdsApiSlice.useListResourcesQuery).mockReturnValue({
      data: makeResources(5),
      isLoading: false,
      error: undefined,
    } as any);
    rerender(<ResourceList group="g" type="clusters" />);

    expect(mockPush).toHaveBeenCalledWith(
      { pathname: '/app/xds/group', query: { name: 'g', type: 'clusters' } },
      undefined,
      { shallow: true },
    );
    expect(screen.getAllByTestId('table-row')).toHaveLength(5);
    expect(screen.getByText('groups/g/clusters/c01')).toBeInTheDocument();
  });
});
