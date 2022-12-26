import { RepoMemberDto } from 'dogma/features/repo/RepoMemberDto';
import { RepoPermissionDto } from 'dogma/features/repo/RepoPermissionDto';

export interface ProjectCreatorDto {
  user: string;
  timestamp: string;
}

export interface ProjectMetadataDto {
  name: string;
  repos: RepoPermissionDto;
  members: RepoMemberDto;
  tokens: RepoTokenDto;
  creation: ProjectCreatorDto;
}

type RepoTokenDto = Map<string, RepoTokenDetailDto>;
export interface RepoTokenDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: ProjectCreatorDto;
}
