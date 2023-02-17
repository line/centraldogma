export interface RepoPermissionDto {
  [key: string]: RepoPermissionDetailDto;
}

export interface RepoPermissionDetailDto {
  name: string;
  perRolePermissions: RepoRolePermissionDto;
  perUserPermissions: PerUserPermissionDto;
  perTokenPermissions: PerTokenPermissonDto;
  creation: RepoCreatorDto;
  removal?: RepoCreatorDto;
}

export interface RepoCreatorDto {
  user: string;
  timestamp: string;
}

export interface RepoRolePermissionDto {
  owner: Array<'READ' | 'WRITE'>;
  member: Array<'READ' | 'WRITE'>;
  guest: Array<'READ' | 'WRITE'>;
}

export type PerUserPermissionDto = Map<string, Array<'READ' | 'WRITE'>>;
export type PerTokenPermissonDto = Map<string, Array<'READ' | 'WRITE'>>;
