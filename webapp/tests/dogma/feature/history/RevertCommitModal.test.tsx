import { fireEvent, waitFor } from '@testing-library/react';
import { RevertCommitModal } from 'dogma/features/history/RevertCommitModal';
import { renderWithProviders } from 'dogma/util/test-utils';

const mockUnwrap = jest.fn();
const mockRevert = jest.fn(() => ({ unwrap: mockUnwrap }));

jest.mock('dogma/features/api/apiSlice', () => {
  const actual = jest.requireActual('dogma/features/api/apiSlice');
  return {
    ...actual,
    useRevertRepositoryMutation: () => [mockRevert, { isLoading: false }],
  };
});

describe('RevertCommitModal', () => {
  beforeEach(() => {
    mockRevert.mockClear();
    mockUnwrap.mockReset();
    mockUnwrap.mockResolvedValue({});
  });

  it('prefills the commit message when opened', async () => {
    const { getByDisplayValue } = renderWithProviders(
      <RevertCommitModal
        isOpen={true}
        onClose={jest.fn()}
        projectName="foo"
        repoName="bar"
        headRevision={4}
        targetRevision={2}
      />,
    );

    await waitFor(() => {
      expect(getByDisplayValue('Revert to r2')).toBeTruthy();
      expect(getByDisplayValue('Rollback repository from r4 to r2.')).toBeTruthy();
    });
  });

  it('submits revert request with target revision and message', async () => {
    const onClose = jest.fn();
    const { getByRole, getByDisplayValue } = renderWithProviders(
      <RevertCommitModal
        isOpen={true}
        onClose={onClose}
        projectName="foo"
        repoName="bar"
        headRevision={4}
        targetRevision={2}
      />,
    );

    await waitFor(() => {
      expect(getByDisplayValue('Revert to r2')).toBeTruthy();
    });

    fireEvent.click(getByRole('button', { name: 'Revert' }));

    await waitFor(() => {
      expect(mockRevert).toHaveBeenCalledWith({
        projectName: 'foo',
        repoName: 'bar',
        data: {
          targetRevision: 2,
          commitMessage: {
            summary: 'Revert to r2',
            detail: 'Rollback repository from r4 to r2.',
          },
        },
      });
    });
  });
});
