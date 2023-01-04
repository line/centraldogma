import { RepoMemberDto } from 'dogma/features/repo/RepoMemberDto';
import { RepoPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { RepoTokenDto } from 'dogma/features/repo/RepoTokenDto';

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
