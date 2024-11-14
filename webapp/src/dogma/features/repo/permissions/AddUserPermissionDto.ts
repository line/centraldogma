export interface AddUserPermissionDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    permission: 'READ' | 'WRITE' | 'REPO_ADMIN';
  };
}
