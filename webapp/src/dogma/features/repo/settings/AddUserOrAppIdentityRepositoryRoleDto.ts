import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';

export interface AddUserOrAppIdentityRepositoryRoleDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    role: RepositoryRole;
  };
}
