import { RepoCreatorDto } from 'dogma/features/repo/RepoPermissionDto';

export type RepoTokenDto = Map<string, RepoTokenDetailDto>;
export interface RepoTokenDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: RepoCreatorDto;
}
