import { UserAndTimestamp } from 'dogma/common/UserAndTimestamp';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';

export interface RepositoriesMetadataDto {
  [key: string]: RepositoryMetadataDto;
}

export interface RepositoryMetadataDto {
  name: string;
  roles: RolesDto;
  creation: UserAndTimestamp;
  removal?: UserAndTimestamp;
}

export interface RolesDto {
  projects: ProjectRolesDto;
  users: UserOrAppIdentityRepositoryRoleDto;
  appIds: UserOrAppIdentityRepositoryRoleDto;
}

export interface ProjectRolesDto {
  member: RepositoryRole | null;
  guest: 'READ' | null;
}

export interface UserOrAppIdentityRepositoryRoleDto {
  [key: string]: RepositoryRole;
}
