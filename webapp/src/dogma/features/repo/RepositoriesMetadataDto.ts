import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';

export interface RepositoriesMetadataDto {
  [key: string]: RepositoryMetadataDto;
}

export interface RepositoryMetadataDto {
  name: string;
  roles: RolesDto;
  creation: RepoCreatorDto;
  removal?: RepoCreatorDto;
}

export interface RolesDto {
  projects: ProjectRolesDto;
  users: UserOrTokenRepositoryRoleDto;
  tokens: UserOrTokenRepositoryRoleDto;
}

export interface ProjectRolesDto {
  member: RepositoryRole | null;
  guest: 'READ' | 'WRITE' | null;
}

export interface UserOrTokenRepositoryRoleDto {
  [key: string]: RepositoryRole;
}

export interface RepoCreatorDto {
  user: string;
  timestamp: string;
}
