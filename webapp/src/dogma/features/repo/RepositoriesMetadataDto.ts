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

// A repository is public when its guest role is READ.
export function isPublicRepo(repo: RepositoryMetadataDto | undefined): boolean {
  return repo?.roles?.projects?.guest === 'READ';
}

export interface UserOrAppIdentityRepositoryRoleDto {
  [key: string]: RepositoryRole;
}
