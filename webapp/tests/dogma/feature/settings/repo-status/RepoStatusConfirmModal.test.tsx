import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from 'dogma/util/test-utils';
import { RepoStatusConfirmModal } from 'dogma/features/settings/repo-status/RepoStatusConfirmModal';
import { ReplicationStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';

const setup = (targetStatus: ReplicationStatus, onConfirm: jest.Mock = jest.fn(), repoName = 'bar') => {
  renderWithProviders(
    <RepoStatusConfirmModal
      isOpen
      onClose={jest.fn()}
      projectName="foo"
      repoName={repoName}
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

  // Making the internal `dogma` repository read-only locks the whole project, which is not obvious
  // from the `foo/dogma` target alone.
  describe('the internal dogma repository', () => {
    it('warns that a read-only dogma repository locks every repository in the project', () => {
      setup('READ_ONLY', jest.fn(), 'dogma');

      expect(screen.getByText('Make project read-only')).toBeInTheDocument();
      expect(screen.getByRole('alert')).toHaveTextContent(
        'blocks all writes to every repository in project foo',
      );
    });

    it('warns that a writable dogma repository leaves individually read-only repositories locked', () => {
      setup('WRITABLE', jest.fn(), 'dogma');

      expect(screen.getByText('Make project writable')).toBeInTheDocument();
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Repositories that were made read-only individually stay read-only',
      );
    });

    it('does not warn about the project scope for a regular repository', () => {
      setup('READ_ONLY');

      expect(screen.getByText('Make repository read-only')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('still requires the full project/repository name to confirm', () => {
      setup('READ_ONLY', jest.fn(), 'dogma');
      const confirmButton = screen.getByRole('button', { name: 'Make read-only' });
      expect(confirmButton).toBeDisabled();

      fireEvent.change(screen.getByPlaceholderText('foo/dogma'), { target: { value: 'foo' } });
      expect(confirmButton).toBeDisabled();

      fireEvent.change(screen.getByPlaceholderText('foo/dogma'), { target: { value: 'foo/dogma' } });
      expect(confirmButton).toBeEnabled();
    });
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
