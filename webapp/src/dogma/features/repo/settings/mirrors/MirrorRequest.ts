export interface MirrorRequest {
  id: string;
  projectName: string;
  schedule?: string;
  direction: 'REMOTE_TO_LOCAL' | 'LOCAL_TO_REMOTE';
  localRepo: string;
  localPath: string;
  remoteScheme: string;
  remoteUrl: string;
  remoteBranch: string;
  remotePath: string;
  gitignore?: string;
  credentialName: string;
  enabled: boolean;
  zone?: string;
  preserveRemoteCommitHistory?: boolean;
}

const GIT_MIRROR_SCHEMES = new Set(['git', 'git+file', 'git+http', 'git+https', 'git+ssh']);

export function isGitMirrorScheme(scheme: string): boolean {
  return GIT_MIRROR_SCHEMES.has(scheme);
}

export interface MirrorDto extends MirrorRequest {
  allow: boolean;
}
