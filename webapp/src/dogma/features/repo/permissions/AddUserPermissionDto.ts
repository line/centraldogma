export interface AddUserPermissionDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    permissions: Array<'READ' | 'WRITE'>;
  };
}
