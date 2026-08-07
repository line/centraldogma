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
import { ResourceHistory } from 'dogma/features/xds/ResourceHistory';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
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

// Monaco cannot mount in JSDOM; the diff editor is only reached via the (unopened) modal, so stub it out.
jest.mock('dogma/common/components/JsonDiffEditor', () => ({
  JsonDiffEditor: () => null,
}));

jest.mock('dogma/features/xds/xdsApiSlice', () => ({
  // Preserve reducerPath/reducer so the Redux store initialises correctly.
  ...jest.requireActual('dogma/features/xds/xdsApiSlice'),
  useGetGroupHistoryQuery: jest.fn(),
}));

function makeCommits(n: number): HistoryDto[] {
  return Array.from({ length: n }, (_, i) => {
    const label = `commit-${String(i + 1).padStart(2, '0')}`;
    return {
      revision: n - i,
      author: { name: 'System', email: 'system@localhost' },
      commitMessage: { summary: label, detail: '', markup: 'PLAINTEXT' },
      pushedAt: '2026-01-01T00:00:00Z',
    };
  });
}

describe('ResourceHistory – URL-persisted pagination', () => {
  beforeEach(() => {
    mockPush.mockClear();
    mockQuery = {};
    jest.mocked(xdsApiSlice.useGetGroupHistoryQuery).mockReturnValue({
      data: makeCommits(25),
      isLoading: false,
      error: undefined,
    } as any);
  });

  it('restores the page from the ?page query param (back-navigation fix)', () => {
    mockQuery = { name: 'g', type: 'history', page: '2' };
    renderWithProviders(<ResourceHistory group="g" />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(10);
    expect(screen.getByText('commit-11')).toBeInTheDocument();
    expect(screen.queryByText('commit-01')).not.toBeInTheDocument();
  });

  it('writes the next page to the URL while preserving the group and section params', async () => {
    const user = userEvent.setup();
    mockQuery = { name: 'g', type: 'history' };
    renderWithProviders(<ResourceHistory group="g" />);

    await user.click(screen.getByRole('button', { name: /next/i }));

    expect(mockPush).toHaveBeenCalledWith(
      { pathname: '/app/xds/group', query: { name: 'g', type: 'history', page: '2' } },
      undefined,
      { shallow: true },
    );
  });

  it('restores the page size from the ?pageSize query param', () => {
    mockQuery = { name: 'g', type: 'history', pageSize: '20' };
    renderWithProviders(<ResourceHistory group="g" />);
    expect(screen.getAllByTestId('table-row')).toHaveLength(20);
  });

  it('writes the selected page size to the URL', async () => {
    const user = userEvent.setup();
    mockQuery = { name: 'g', type: 'history' };
    renderWithProviders(<ResourceHistory group="g" />);

    await user.selectOptions(screen.getByRole('combobox'), '20');

    expect(mockPush).toHaveBeenCalledWith(
      { pathname: '/app/xds/group', query: { name: 'g', type: 'history', pageSize: '20' } },
      undefined,
      { shallow: true },
    );
  });
});
