import { RepositoriesMetadataDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { AppMemberDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { AppTokenDto } from 'dogma/features/project/settings/tokens/AppTokenDto';

export interface ProjectCreatorDto {
  user: string;
  timestamp: string;
}

export interface ProjectMetadataDto {
  name: string;
  repos: RepositoriesMetadataDto;
  members: AppMemberDto;
  appIds: AppTokenDto;
  creation: ProjectCreatorDto;
}
