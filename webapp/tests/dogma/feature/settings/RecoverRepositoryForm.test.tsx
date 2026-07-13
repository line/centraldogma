import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import RecoverRepositoryForm from 'dogma/features/settings/recovery/RecoverRepositoryForm';
import {
  useGetProjectsQuery,
  useGetReplicasQuery,
  useGetReposQuery,
  useRecoverRepositoryMutation,
} from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetProjectsQuery: jest.fn(),
  useGetReposQuery: jest.fn(),
  useGetReplicasQuery: jest.fn(),
  useRecoverRepositoryMutation: jest.fn(),
}));

// chakra-react-select does not render under jsdom; replace it with a plain <select> that keeps the
// same {value, label} option contract.
jest.mock('chakra-react-select', () => ({
  Select: function MockSelect(props: {
    id: string;
    options: Array<{ value: string | number; label: string }>;
    value: { value: string | number } | null;
    onChange: (option: { value: string | number; label: string } | null) => void;
    isDisabled?: boolean;
  }) {
    const { id, options, value, onChange, isDisabled } = props;
    return (
      <select
        data-testid={id}
        disabled={isDisabled}
        value={value != null ? String(value.value) : ''}
        onChange={(e) => {
          const selected = options.find((option) => String(option.value) === e.target.value);
          onChange(selected ?? null);
        }}
      >
        <option value="" />
        {options.map((option) => (
          <option key={String(option.value)} value={String(option.value)}>
            {option.label}
          </option>
        ))}
      </select>
    );
  },
}));

const recoverTrigger = jest.fn();

const renderForm = () => {
  (useGetProjectsQuery as jest.Mock).mockReturnValue({ data: [{ name: 'foo' }], isLoading: false });
  (useGetReposQuery as jest.Mock).mockReturnValue({ data: [{ name: 'bar' }], isFetching: false });
  (useGetReplicasQuery as jest.Mock).mockReturnValue({
    data: [
      { serverId: 1, host: 'replica1.example.com', current: true },
      { serverId: 2, host: 'replica2.example.com', current: false },
    ],
  });
  recoverTrigger.mockReturnValue({ unwrap: () => Promise.resolve({ status: 'REQUESTED' }) });
  (useRecoverRepositoryMutation as jest.Mock).mockReturnValue([recoverTrigger, { isLoading: false }]);
  return renderWithProviders(<RecoverRepositoryForm />);
};

const recoverButton = () => screen.getAllByRole('button', { name: 'Recover' })[0];
const modalRecoverButton = () => screen.getAllByRole('button', { name: 'Recover' }).at(-1)!;

const fillForm = async () => {
  await userEvent.selectOptions(screen.getByTestId('recovery-project-select'), 'foo');
  await userEvent.selectOptions(screen.getByTestId('recovery-repo-select'), 'bar');
  await userEvent.selectOptions(screen.getByTestId('recovery-source-select'), '2');
};

describe('RecoverRepositoryForm', () => {
  beforeEach(() => jest.clearAllMocks());

  it('disables Recover until the project, repository and source server are all chosen', async () => {
    renderForm();
    expect(recoverButton()).toBeDisabled();

    await userEvent.selectOptions(screen.getByTestId('recovery-project-select'), 'foo');
    expect(recoverButton()).toBeDisabled();

    await userEvent.selectOptions(screen.getByTestId('recovery-repo-select'), 'bar');
    expect(recoverButton()).toBeDisabled();

    await userEvent.selectOptions(screen.getByTestId('recovery-source-select'), '2');
    expect(recoverButton()).toBeEnabled();
  });

  it('requires typing the full project/repository name before the recovery can run', async () => {
    renderForm();
    await fillForm();
    await userEvent.click(recoverButton());

    expect(modalRecoverButton()).toBeDisabled();

    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/wrong');
    expect(modalRecoverButton()).toBeDisabled();
    expect(recoverTrigger).not.toHaveBeenCalled();

    await userEvent.clear(confirmInput);
    await userEvent.type(confirmInput, 'foo/bar');
    expect(modalRecoverButton()).toBeEnabled();

    await userEvent.click(modalRecoverButton());
    expect(recoverTrigger).toHaveBeenCalledWith({
      projectName: 'foo',
      repoName: 'bar',
      fromRevision: 2,
      sourceServerId: 2,
    });
  });

  it('passes the edited start revision to the recovery', async () => {
    renderForm();
    await fillForm();

    const revisionInput = screen.getByRole('spinbutton');
    await userEvent.clear(revisionInput);
    await userEvent.type(revisionInput, '17');

    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    expect(recoverTrigger).toHaveBeenCalledWith(
      expect.objectContaining({ fromRevision: 17, sourceServerId: 2 }),
    );
  });

  it('shows a server rejection inline and keeps the modal open', async () => {
    renderForm();
    recoverTrigger.mockReturnValue({
      unwrap: () => Promise.reject({ data: { message: 'recovery rejected by the server: mock-reason' } }),
    });
    await fillForm();
    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    // The reason is displayed inline and the modal stays open for a retry.
    expect(await screen.findByText(/mock-reason/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('foo/bar')).toBeInTheDocument();
  });

  it('shows a persistent success message after a completed recovery', async () => {
    renderForm();
    recoverTrigger.mockReturnValue({
      unwrap: () => Promise.resolve({ status: 'COMPLETED', headRevision: 3 }),
    });
    await fillForm();
    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    expect(await screen.findByText(/Recovery of foo\/bar completed at revision 3/)).toBeInTheDocument();
  });
});
