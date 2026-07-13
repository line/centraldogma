import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import RecoverRepositoryForm, {
  buildVerificationScript,
  conciseErrorMessage,
} from 'dogma/features/settings/recovery/RecoverRepositoryForm';
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

describe('buildVerificationScript', () => {
  const result = {
    projectName: 'foo',
    repoName: 'bar',
    sourceServerId: 2,
    response: { status: 'REQUESTED' as const },
  };
  const replicas = [
    { serverId: 1, host: 'replica1.example.com', current: true },
    { serverId: 2, host: 'replica2.example.com', current: false },
  ];

  afterEach(() => {
    delete process.env.NEXT_PUBLIC_HOST;
  });

  it('skips certificate verification over https, where a replica is reached by its own host name', () => {
    process.env.NEXT_PUBLIC_HOST = 'https://dogma.example.com';
    const script = buildVerificationScript(result, replicas);
    // No explicit port: https defaults to 443.
    expect(script).toContain(
      'curl -sk -H "Authorization: Bearer $CD_TOKEN" ' +
        'https://replica1.example.com:443/api/v1/projects/foo/repos/bar | jq .headRevision  # server 1',
    );
    expect(script).toContain('# -k skips certificate verification');
  });

  it('does not skip certificate verification over http', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const script = buildVerificationScript(result, replicas);
    expect(script).toContain(
      'curl -s -H "Authorization: Bearer $CD_TOKEN" ' +
        'http://replica2.example.com:36462/api/v1/projects/foo/repos/bar | jq .headRevision  ' +
        '# server 2 (source)',
    );
    expect(script).not.toContain('-k');
  });
});

describe('conciseErrorMessage', () => {
  it('drops the Java stack trace a system administrator receives', () => {
    // The server answers a system administrator with the message and a verbose stack trace.
    const error = {
      data: {
        message: 'The repository must be read-only before recovery: foo/bar',
        detail:
          'java.lang.IllegalArgumentException: The repository must be read-only before recovery: foo/bar\n' +
          '\tat com.linecorp.centraldogma.server.internal.api.RepositoryServiceV1.recover(' +
          'RepositoryServiceV1.java:354)\n' +
          '\tat com.linecorp.armeria.internal.server.annotation.DefaultAnnotatedService.invoke(' +
          'DefaultAnnotatedService.java:475)',
      },
    };
    expect(conciseErrorMessage(error)).toBe('The repository must be read-only before recovery: foo/bar');
  });

  it('keeps a message that carries no stack trace', () => {
    expect(conciseErrorMessage({ data: { message: 'boom' } })).toBe('boom');
  });
});

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

  it('shows a copyable per-replica head verification script after a REQUESTED recovery', async () => {
    renderForm(); // The default mock resolves with REQUESTED.
    await fillForm();
    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    // One curl per replica against the recovered repository, with the source replica marked. The page
    // is served over http here, so no certificate to skip. The script is syntax-highlighted, so read
    // the element's text rather than matching a single text node.
    const script = (await screen.findByTestId('recovery-verification-script')).textContent ?? '';
    expect(script).toContain('CD_TOKEN=');
    expect(script).toContain(
      'curl -s -H "Authorization: Bearer $CD_TOKEN" ' +
        'http://replica1.example.com:36462/api/v1/projects/foo/repos/bar | jq .headRevision  # server 1',
    );
    expect(script).toContain(
      'curl -s -H "Authorization: Bearer $CD_TOKEN" ' +
        'http://replica2.example.com:36462/api/v1/projects/foo/repos/bar | jq .headRevision  ' +
        '# server 2 (source)',
    );
    expect(script).not.toContain('-sk');
    expect(screen.getByRole('button', { name: /Copy/ })).toBeInTheDocument();
  });
});
