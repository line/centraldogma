import { RepoCreatorDto } from 'dogma/features/repo/RepoPermissionDto';

export type RepoMemberDto = Map<string, RepoMemberDetailDto>;

export interface RepoMemberDetailDto {
  login: string;
  role: string;
  creation: RepoCreatorDto;
}
