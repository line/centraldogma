import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from 'dogma/util/test-utils';
import { RepoStatusConfirmModal } from 'dogma/features/settings/repo-status/RepoStatusConfirmModal';
import { ReplicationStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';

const setup = (targetStatus: ReplicationStatus, onConfirm: jest.Mock = jest.fn()) => {
  renderWithProviders(
    <RepoStatusConfirmModal
      isOpen
      onClose={jest.fn()}
      projectName="foo"
      repoName="bar"
      targetStatus={targetStatus}
      onConfirm={onConfirm}
      isLoading={false}
    />,
  );
  return onConfirm;
};

const labelOf = (status: ReplicationStatus) => (status === 'READ_ONLY' ? 'Make read-only' : 'Make writable');

describe('RepoStatusConfirmModal', () => {
  it.each(['READ_ONLY', 'WRITABLE'] as const)(
    'keeps the %s confirm button disabled until the exact project/repo is typed',
    (status) => {
      setup(status);
      const confirmButton = screen.getByRole('button', { name: labelOf(status) });
      expect(confirmButton).toBeDisabled();

      // A partial/wrong name keeps it disabled.
      fireEvent.change(screen.getByPlaceholderText('foo/bar'), { target: { value: 'foo/ba' } });
      expect(confirmButton).toBeDisabled();

      // The exact name enables it.
      fireEvent.change(screen.getByPlaceholderText('foo/bar'), { target: { value: 'foo/bar' } });
      expect(confirmButton).toBeEnabled();
    },
  );

  it('calls onConfirm only after the exact name is typed', () => {
    const onConfirm = setup('WRITABLE');

    // Clicking while disabled does nothing.
    fireEvent.click(screen.getByRole('button', { name: 'Make writable' }));
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.change(screen.getByPlaceholderText('foo/bar'), { target: { value: 'foo/bar' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make writable' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('clears the typed confirmation when reused for a different target', () => {
    const { rerender } = renderWithProviders(
      <RepoStatusConfirmModal
        isOpen
        onClose={jest.fn()}
        projectName="foo"
        repoName="bar"
        targetStatus="WRITABLE"
        onConfirm={jest.fn()}
        isLoading={false}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('foo/bar'), { target: { value: 'foo/bar' } });
    expect(screen.getByPlaceholderText('foo/bar')).toHaveValue('foo/bar');

    // The list re-renders after a status change and the modal instance is reused for another row.
    rerender(
      <RepoStatusConfirmModal
        isOpen
        onClose={jest.fn()}
        projectName="baz"
        repoName="qux"
        targetStatus="WRITABLE"
        onConfirm={jest.fn()}
        isLoading={false}
      />,
    );
    expect(screen.getByPlaceholderText('baz/qux')).toHaveValue('');
  });
});
