import { RepoPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { AppMemberDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { AppTokenDto } from 'dogma/features/project/settings/tokens/AppTokenDto';

export interface ProjectCreatorDto {
  user: string;
  timestamp: string;
}

export interface ProjectMetadataDto {
  name: string;
  repos: RepoPermissionDto;
  members: AppMemberDto;
  tokens: AppTokenDto;
  creation: ProjectCreatorDto;
}
