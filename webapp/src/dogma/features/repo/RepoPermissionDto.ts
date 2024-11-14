export interface RepoPermissionDto {
  [key: string]: RepoPermissionDetailDto;
}

export interface RepoPermissionDetailDto {
  name: string;
  perRolePermissions: RepoRolePermissionDto;
  perUserPermissions: PerUserPermissionDto;
  perTokenPermissions: PerTokenPermissionDto;
  creation: RepoCreatorDto;
  removal?: RepoCreatorDto;
}

export interface RepoCreatorDto {
  user: string;
  timestamp: string;
}

export interface RepoRolePermissionDto {
  member: 'READ' | 'WRITE' | 'REPO_ADMIN' | null;
  guest: 'READ' | 'WRITE' | null;
}

export interface PerUserPermissionDto {
  [key: string]: 'READ' | 'WRITE' | 'REPO_ADMIN';
}

export interface PerTokenPermissionDto {
  [key: string]: 'READ' | 'WRITE' | 'REPO_ADMIN';
}
