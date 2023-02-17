import { AppMemberDto } from 'dogma/features/metadata/AppMemberDto';
import { RepoPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { AppTokenDto } from 'dogma/features/metadata/AppTokenDto';

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
