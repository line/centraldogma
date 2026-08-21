import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from 'dogma/util/test-utils';
import RecoverRepositoryForm, {
  buildVerificationScript,
  conciseErrorMessage,
} from 'dogma/features/settings/recovery/RecoverRepositoryForm';
import {
  useGetHistoryQuery,
  useGetProjectsQuery,
  useGetReplicasQuery,
  useGetReposQuery,
  useRecoverRepositoryMutation,
} from 'dogma/features/api/apiSlice';

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetHistoryQuery: jest.fn(),
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

const renderForm = (repoStatus: 'READ_ONLY' | 'WRITABLE' = 'READ_ONLY') => {
  (useGetProjectsQuery as jest.Mock).mockReturnValue({ data: [{ name: 'foo' }], isLoading: false });
  (useGetReposQuery as jest.Mock).mockReturnValue({
    data: [{ name: 'bar', status: repoStatus }],
    isFetching: false,
  });
  (useGetHistoryQuery as jest.Mock).mockReturnValue({
    data: [
      {
        revision: 4,
        author: { name: 'minwoox', email: 'minwoox@example.com' },
        commitMessage: { summary: 'Update a.json', detail: '', markup: 'PLAINTEXT' },
        pushedAt: '2026-08-19T05:00:00Z',
      },
    ],
    isFetching: false,
  });
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
    toRevision: 4,
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
    expect(script).toContain('head_of 1 replica1.example.com:443');
    expect(script).toContain('head_of 2 replica2.example.com:443  # source');
    expect(script).toContain('curl -sfk -m 10 -H "Authorization: Bearer $CD_TOKEN"');
    expect(script).toContain('"https://$2/api/v1/projects/foo/repos/bar/head"');
  });

  it('does not skip certificate verification over http', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const script = buildVerificationScript(result, replicas);
    expect(script).toContain('head_of 1 replica1.example.com:36462');
    expect(script).toContain('curl -sf -m 10 -H "Authorization: Bearer $CD_TOKEN"');
    expect(script).not.toContain('-sfk');
    // A revision alone cannot detect a divergence, so the script must compare the tree ID.
    expect(script).toContain('treeId');
    expect(script).not.toContain('.revision');
    // And the head must be checked against the revision the recovery asked for, not merely printed.
    expect(script).toContain('TO_REVISION=4');
    // Re-running in the same shell must not inherit the previous run's state.
    expect(script).toContain("SEEN=''");
    expect(script).toContain("SOURCE_TREE=''");
    // The tree is compared, not merely printed: an operator must not have to eyeball 40 hex digits.
    expect(script).toContain('SOURCE_TREE=$tree');
    expect(script).toContain('same="DIFFERENT TREE"');
    expect(script).toContain('*"\\"revision\\":$TO_REVISION,"*) reset="reset ok"');
  });

  // A token is required, so an unauthenticated curl answers 401, which -f turns into no output at all.
  // Printing nothing there would read exactly like a converged replica, so the script must name the
  // failure.
  it('reports an unreachable replica instead of printing nothing', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const script = buildVerificationScript(result, replicas);
    // -f makes curl exit non-zero (and print no body) on 4xx/5xx, and the fallback names it.
    expect(script).toContain('-sf');
    expect(script).toContain('REQUEST FAILED');
    expect(script).toContain('REQUEST FAILED is not a pass either');
  });

  // The roster carries no port, so every address is seeded from the browser's. Two replicas behind one
  // host then collapse onto one address, and the script would poll that server twice and look converged.
  it('warns when two replicas are seeded with the same address', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const sameHost = [
      { serverId: 1, host: 'dogma.example.com', current: true },
      { serverId: 2, host: 'dogma.example.com', current: false },
    ];
    const script = buildVerificationScript(result, sameHost);
    expect(script).toContain('# WARNING: replicas share a host');
    expect(script).toContain('wrongly looks converged');
    // And the script refuses to poll one address twice, in case the warning is skimmed past.
    expect(script).toContain('DUPLICATE ADDRESS');
    // The warning is the only guard left, so the colliding addresses must be visible under it.
    expect(script).toContain('head_of 1 dogma.example.com:36462');
    expect(script).toContain('head_of 2 dogma.example.com:36462  # source');
  });

  it('does not warn when every replica has its own address', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const script = buildVerificationScript(result, replicas);
    expect(script).not.toContain('# WARNING: replicas share a host');
    expect(script).toContain('the ports are a guess');
  });

  // The placeholder is unquoted bash metacharacters, so an unquoted assignment is a syntax error the
  // moment the operator pastes it.
  it('quotes the token placeholder', () => {
    process.env.NEXT_PUBLIC_HOST = 'http://dogma.example.com:36462';
    const script = buildVerificationScript(result, replicas);
    expect(script).toContain("CD_TOKEN='<paste a system administrator token>'");
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
      toRevision: 2,
      sourceServerId: 2,
    });
  });

  it('lists the recent revisions to pick the range from', async () => {
    renderForm();
    await fillForm();

    expect(screen.getByText('Update a.json')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
  });

  // A recovery is rejected while the repository is writable, so say so before the operator tries.
  it('blocks a recovery while the repository is writable, and says why', async () => {
    renderForm('WRITABLE');
    await fillForm();

    expect(screen.getByText(/Make it read-only/)).toBeInTheDocument();
    expect(recoverButton()).toBeDisabled();
  });

  it('passes the edited revision range to the recovery', async () => {
    renderForm();
    await fillForm();

    const [fromInput, toInput] = screen.getAllByRole('spinbutton');
    await userEvent.clear(fromInput);
    await userEvent.type(fromInput, '17');
    await userEvent.clear(toInput);
    await userEvent.type(toInput, '19');

    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    expect(recoverTrigger).toHaveBeenCalledWith(
      expect.objectContaining({ fromRevision: 17, toRevision: 19, sourceServerId: 2 }),
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

  it('does not claim convergence on the RECOVERING path, and still offers the script', async () => {
    renderForm();
    recoverTrigger.mockReturnValue({
      unwrap: () => Promise.resolve({ status: 'RECOVERING', toRevision: 3 }),
    });
    await fillForm();
    await userEvent.click(recoverButton());
    const confirmInput = screen.getByPlaceholderText('foo/bar');
    await userEvent.type(confirmInput, 'foo/bar');
    await userEvent.click(modalRecoverButton());

    // RECOVERING only means the source originated the recovery; the others apply it asynchronously.
    expect(await screen.findByText(/has not converged yet/)).toBeInTheDocument();
    // The verification script must be offered on this path too.
    const script = (await screen.findByTestId('recovery-verification-script')).textContent ?? '';
    expect(script).toContain('/head');
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
    expect(script).toContain("CD_TOKEN='<paste a system administrator token>'");
    expect(script).toContain('head_of 1 replica1.example.com:36462');
    expect(script).toContain('"http://$2/api/v1/projects/foo/repos/bar/head"');
    expect(script).toContain('# source');
    expect(script).not.toContain('-sfk');
    expect(screen.getByRole('button', { name: /Copy/ })).toBeInTheDocument();
  });
});
