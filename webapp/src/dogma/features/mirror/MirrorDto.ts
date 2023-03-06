export interface MirrorDto {
  index: number;
  id: string;
  projectName: string;
  schedule: string;
  direction: 'REMOTE_TO_LOCAL' | 'LOCAL_TO_REMOTE';
  localRepo: string;
  localPath: string;
  remoteScheme: string;
  remoteUrl: string;
  remoteBranch: string;
  remotePath: string;
  gitignore?: string;
  credentialId?: string;
  enabled: boolean;
}
