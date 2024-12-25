import { RepositoryRole } from '../RepositoriesMetadataDto';

export interface AddUserRepositoryRoleDto {
  projectName: string;
  repoName: string;
  data: {
    id: string;
    role: RepositoryRole;
  };
}
