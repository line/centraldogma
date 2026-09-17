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
    writeText.mockClear();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
  });

  it('copies the upstream commit as a Spring Cloud Config label', async () => {
    renderWithProviders(<CommitSha label="Upstream" sha={sha} copyValue={`dogma-${sha}`} />);

    expect(screen.getByText('0123456')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Copy Spring label' }));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(`dogma-${sha}`));
  });

  it('shows the full commit ID when abbreviation is disabled', () => {
    renderWithProviders(<CommitSha label="Central Dogma" sha={sha} abbreviated={false} />);

    expect(screen.getByText(sha)).toBeInTheDocument();
  });
});
