import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';

export interface AddUserOrTokenRepositoryRoleDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    role: RepositoryRole;
  };
}
