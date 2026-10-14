import { screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from 'dogma/util/test-utils';
import MirrorForm from 'dogma/features/repo/settings/mirrors/MirrorForm';
import { MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: {},
    pathname: '/',
    push: jest.fn(),
  }),
}));

jest.mock('dogma/features/api/apiSlice', () => ({
  ...jest.requireActual('dogma/features/api/apiSlice'),
  useGetProjectCredentialsQuery: jest.fn().mockReturnValue({
    data: [{ id: 'test-credential', name: 'projects/myProject/credentials/test-credential' }],
  }),
  useGetRepoCredentialsQuery: jest.fn().mockReturnValue({
    data: [],
  }),
  useGetMirrorConfigQuery: jest.fn().mockReturnValue({
    data: { zonePinned: false },
  }),
}));

// Mock chakra-react-select which doesn't work in JSDOM
jest.mock('chakra-react-select', () => ({
  Select: ({ id, name, options, onChange, value, placeholder, ...rest }: any) => (
    <select
      id={id}
      data-testid={id}
      name={name}
      value={value?.value || ''}
      onChange={(e) => {
        const selected = options
          ?.flatMap((opt: any) => (opt.options ? opt.options : [opt]))
          .find((opt: any) => opt.value === e.target.value);
        onChange(selected || null);
      }}
    >
      <option value="">{placeholder || 'Select...'}</option>
      {options?.flatMap((opt: any) =>
        opt.options
          ? opt.options.map((o: any) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))
          : [
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>,
            ],
      )}
    </select>
  ),
}));

const emptyMirror: MirrorRequest = {
  id: '',
  direction: 'REMOTE_TO_LOCAL',
  schedule: '0 * * * * ?',
  projectName: 'myProject',
  localRepo: '',
  localPath: '/',
  remoteScheme: '',
  remoteUrl: '',
  remoteBranch: 'main',
  remotePath: '/',
  credentialName: null,
  gitignore: null,
  enabled: false,
};

const mockOnSubmit = jest.fn().mockResolvedValue(undefined);

function renderMirrorForm(defaultValue: MirrorRequest = emptyMirror) {
  return renderWithProviders(
    <MirrorForm
      projectName="myProject"
      repoName="myRepo"
      defaultValue={defaultValue}
      onSubmit={mockOnSubmit}
      isWaitingResponse={false}
    />,
  );
}

describe('MirrorForm', () => {
  beforeEach(() => {
    mockOnSubmit.mockClear();
  });

  it('renders dogma and dogma+https in the scheme dropdown', () => {
    const { container } = renderMirrorForm();
    const schemeSelect = container.querySelector('#remoteScheme') as HTMLSelectElement;
    const options = Array.from(schemeSelect.querySelectorAll('option')).map((opt) => opt.textContent);
    expect(options).toContain('dogma');
    expect(options).toContain('dogma+https');
  });

  it('renders git schemes in the scheme dropdown', () => {
    const { container } = renderMirrorForm();
    const schemeSelect = container.querySelector('#remoteScheme') as HTMLSelectElement;
    const options = Array.from(schemeSelect.querySelectorAll('option')).map((opt) => opt.textContent);
    expect(options).toContain('git+ssh');
    expect(options).toContain('git+http');
    expect(options).toContain('git+https');
  });

  it('shows the branch field by default', () => {
    renderMirrorForm();
    expect(screen.getByPlaceholderText('main')).toBeInTheDocument();
  });

  it('hides the branch field when dogma scheme is selected', () => {
    const dogmaMirror: MirrorRequest = {
      ...emptyMirror,
      remoteScheme: 'dogma',
    };
    renderMirrorForm(dogmaMirror);
    expect(screen.queryByPlaceholderText('main')).not.toBeInTheDocument();
  });

  it('hides the branch field when dogma+https scheme is selected', () => {
    const dogmaHttpsMirror: MirrorRequest = {
      ...emptyMirror,
      remoteScheme: 'dogma+https',
    };
    renderMirrorForm(dogmaHttpsMirror);
    expect(screen.queryByPlaceholderText('main')).not.toBeInTheDocument();
  });

  it('shows dogma URL placeholder when dogma scheme is selected', () => {
    const dogmaMirror: MirrorRequest = {
      ...emptyMirror,
      remoteScheme: 'dogma',
    };
    renderMirrorForm(dogmaMirror);
    expect(screen.getByPlaceholderText('my-cd.com/myproject/myrepo.dogma')).toBeInTheDocument();
  });

  it('shows git URL placeholder when git scheme is selected', () => {
    const gitMirror: MirrorRequest = {
      ...emptyMirror,
      remoteScheme: 'git+ssh',
    };
    renderMirrorForm(gitMirror);
    expect(screen.getByPlaceholderText('my.git.com/org/myrepo.git')).toBeInTheDocument();
  });
});
