import { renderWithProviders } from 'dogma/util/test-utils';
import MirrorView from 'dogma/features/repo/settings/mirrors/MirrorView';
import { MirrorRequest } from 'dogma/features/repo/settings/mirrors/MirrorRequest';

jest.mock('next/router', () => ({
  useRouter: () => ({
    isReady: true,
    query: {},
    pathname: '/',
    push: jest.fn(),
  }),
}));

const baseMirror: MirrorRequest = {
  id: 'test-mirror',
  projectName: 'myProject',
  schedule: '0 * * * * ?',
  direction: 'REMOTE_TO_LOCAL',
  localRepo: 'myRepo',
  localPath: '/',
  remoteScheme: 'git+ssh',
  remoteUrl: 'github.com/org/repo.git',
  remoteBranch: 'main',
  remotePath: '/',
  credentialName: 'projects/myProject/credentials/my-credential',
  enabled: true,
};

describe('MirrorView', () => {
  it('renders remote path with branch for git mirrors', () => {
    const { getByText } = renderWithProviders(
      <MirrorView projectName="myProject" repoName="myRepo" mirror={baseMirror} />,
    );
    expect(getByText(/git\+ssh:\/\/github\.com\/org\/repo\.git\/#main/)).toBeInTheDocument();
  });

  it('renders remote path without branch for dogma mirrors', () => {
    const dogmaMirror: MirrorRequest = {
      ...baseMirror,
      id: 'dogma-mirror',
      remoteScheme: 'dogma',
      remoteUrl: 'my-cd.com/myproject/myrepo.dogma',
      remoteBranch: '',
      remotePath: '/',
    };
    const { container } = renderWithProviders(
      <MirrorView projectName="myProject" repoName="myRepo" mirror={dogmaMirror} />,
    );
    // Find the remote path row's code element (not the local path row)
    const remotePathCodes = Array.from(container.querySelectorAll('code')).filter((el) =>
      el.textContent.includes('my-cd.com'),
    );
    expect(remotePathCodes.length).toBe(1);
    expect(remotePathCodes[0].textContent).toContain('dogma://my-cd.com/myproject/myrepo.dogma');
    expect(remotePathCodes[0].textContent).not.toContain('#');
  });

  it('renders remote path without branch for dogma+https mirrors', () => {
    const dogmaHttpsMirror: MirrorRequest = {
      ...baseMirror,
      id: 'dogma-https-mirror',
      remoteScheme: 'dogma+https',
      remoteUrl: 'my-cd.com/myproject/myrepo.dogma',
      remoteBranch: '',
      remotePath: '/config/',
    };
    const { container } = renderWithProviders(
      <MirrorView projectName="myProject" repoName="myRepo" mirror={dogmaHttpsMirror} />,
    );
    const remotePathCode = Array.from(container.querySelectorAll('code')).find((el) =>
      el.textContent.includes('dogma+https://'),
    );
    expect(remotePathCode).toBeDefined();
    expect(remotePathCode.textContent).toContain('dogma+https://my-cd.com/myproject/myrepo.dogma');
    expect(remotePathCode.textContent).toContain('/config/');
    expect(remotePathCode.textContent).not.toContain('#');
  });
});
