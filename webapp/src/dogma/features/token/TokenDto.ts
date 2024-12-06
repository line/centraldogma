import { RepoCreatorDto } from 'dogma/features/repo/RepoPermissionDto';

export interface TokenDto {
  appId: string;
  secret?: string;
  systemAdmin: boolean;
  creation: RepoCreatorDto;
  deactivation?: RepoCreatorDto;
}
