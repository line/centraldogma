import { RepositoriesMetadataDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { AppMemberDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { AppIdDto } from 'dogma/features/project/settings/app-identities/AppIdDto';

export interface ProjectCreatorDto {
  user: string;
  timestamp: string;
}

export interface ProjectMetadataDto {
  name: string;
  repos: RepositoriesMetadataDto;
  members: AppMemberDto;
  appIds: AppIdDto;
  creation: ProjectCreatorDto;
}
