export interface AddUserPermissionDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    permissions: 'READ' | 'WRITE' | 'REPO_ADMIN';
  };
}
