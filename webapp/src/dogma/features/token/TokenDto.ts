import { RepoCreatorDto } from 'dogma/features/repo/RepoPermissionDto';

export interface TokenDto {
  appId: string;
  secret?: string;
  admin: boolean;
  creation: RepoCreatorDto;
  deactivation?: RepoCreatorDto;
}
