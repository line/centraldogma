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
import { GroupList } from 'dogma/features/xds/GroupList';
import { GroupDto } from 'dogma/features/xds/XdsTypes';

const mockPush = jest.fn();
let mockQuery: Record<string, string | string[]> = {};

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: mockQuery,
    pathname: '/app/xds',
    push: mockPush,
  }),
}));

// Ids are zero-padded so text assertions ('group-01' vs 'group-11') are unambiguous.
function makeGroups(n: number): GroupDto[] {
  return Array.from({ length: n }, (_, i) => ({ id: `group-${String(i + 1).padStart(2, '0')}` }));
}

describe('GroupList – URL-persisted pagination', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockQuery = {};
  });

  it('renders the first page by default', () => {
    renderWithProviders(<GroupList groups={makeGroups(25)} />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(10);
    expect(screen.getByText('group-01')).toBeInTheDocument();
    expect(screen.queryByText('group-11')).not.toBeInTheDocument();
  });

  it('restores the page from the ?page query param (back-navigation fix)', () => {
    mockQuery = { page: '2' };
    renderWithProviders(<GroupList groups={makeGroups(25)} />);
    // Page 2 shows groups 11–20, not 1–10.
    expect(screen.getAllByTestId('table-row')).toHaveLength(10);
    expect(screen.getByText('group-11')).toBeInTheDocument();
    expect(screen.queryByText('group-01')).not.toBeInTheDocument();
  });

  it('restores the page size from the ?pageSize query param', () => {
    mockQuery = { pageSize: '20' };
    renderWithProviders(<GroupList groups={makeGroups(25)} />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(20);
    expect(screen.getByText('group-20')).toBeInTheDocument();
    expect(screen.queryByText('group-21')).not.toBeInTheDocument();
    expect(screen.getByRole('combobox')).toHaveValue('20');
  });

  it('writes the next page to the URL with shallow routing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupList groups={makeGroups(25)} />);

    await user.click(screen.getByRole('button', { name: /next/i }));

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { page: '2' } }, undefined, {
      shallow: true,
    });
  });

  it('writes the selected page size to the URL', async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupList groups={makeGroups(25)} />);

    await user.selectOptions(screen.getByRole('combobox'), '20');

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { pageSize: '20' } }, undefined, {
      shallow: true,
    });
  });

  it('drops the page param when returning to the first page', async () => {
    const user = userEvent.setup();
    mockQuery = { page: '2' };
    renderWithProviders(<GroupList groups={makeGroups(25)} />);

    await user.click(screen.getByRole('button', { name: /previous/i }));

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: {} }, undefined, { shallow: true });
  });

  it('resets to the first page when the filter changes so it cannot strand the user on an empty page', async () => {
    const user = userEvent.setup();
    mockQuery = { page: '2' };
    renderWithProviders(<GroupList groups={makeGroups(25)} />);

    // Filtering from page 2 to a single-page result set must move back to page 1 and render its rows, not
    // leave the user stranded on a now-nonexistent page 2. 'group-0' matches only group-01..group-09.
    await user.type(screen.getByPlaceholderText(/search groups/i), 'group-0');

    expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: {} }, undefined, { shallow: true });
    expect(screen.getByText('group-01')).toBeInTheDocument();
    expect(screen.queryByText('group-11')).not.toBeInTheDocument();
  });

  it('clamps an out-of-range ?page to the last page and corrects the URL', () => {
    mockQuery = { page: '9' };
    renderWithProviders(<GroupList groups={makeGroups(25)} />);

    // 25 groups / 10 per page = 3 pages; page 9 is out of range, so it is clamped to the last page (3).
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/app/xds', query: { page: '3' } }, undefined, {
      shallow: true,
    });
    expect(screen.getAllByTestId('table-row')).toHaveLength(5);
    expect(screen.getByText('group-21')).toBeInTheDocument();
  });
});
