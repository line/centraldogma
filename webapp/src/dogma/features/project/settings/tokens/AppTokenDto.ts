import { RepoCreatorDto } from 'dogma/features/repo/RepositoriesMetadataDto';

export type AppTokenDto = Map<string, AppTokenDetailDto>;
export interface AppTokenDetailDto {
  appId: string;
  role: 'MEMBER' | 'OWNER';
  creation: RepoCreatorDto;
}
