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

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { CommitSha } from 'dogma/features/history/CommitSha';
import { renderWithProviders } from 'dogma/util/test-utils';

describe('CommitSha', () => {
  const sha = '0123456789abcdef0123456789abcdef01234567';
  const writeText = jest.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    writeText.mockReset().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
  });

  it('copies the upstream commit SHA', async () => {
    const { store } = renderWithProviders(<CommitSha label="Upstream" sha={sha} />);

    expect(screen.getByText('0123456')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Copy Upstream SHA' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(sha));
    await waitFor(() => expect(store.getState().notification.type).toBe('success'));
  });

  it.each(['permission denied', 'API unavailable'])('reports a copy failure: %s', async (failure) => {
    if (failure === 'API unavailable') {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
    } else {
      writeText.mockRejectedValue(new DOMException('Clipboard access denied', 'NotAllowedError'));
    }
    const { store } = renderWithProviders(<CommitSha label="Upstream" sha={sha} />);

    fireEvent.click(screen.getByRole('button', { name: 'Copy Upstream SHA' }));

    await waitFor(() => expect(store.getState().notification.type).toBe('error'));
    expect(store.getState().notification.title).toBe('Failed to copy Upstream SHA');
    expect(store.getState().notification.description).toBe('Clipboard access is unavailable or was denied.');
  });

  it('shows the full upstream commit ID when abbreviation is disabled', () => {
    renderWithProviders(<CommitSha label="Upstream" sha={sha} abbreviated={false} />);

    expect(screen.getByText(sha)).toBeInTheDocument();
  });
});
