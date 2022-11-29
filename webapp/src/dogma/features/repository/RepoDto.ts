export interface CreatorDto {
  name: string;
  email: string;
}

export interface RepoDto {
  name: string;
  creator: CreatorDto;
  headRevision: number;
  url: string;
  createdAt: string;
}

export interface RepoDataTableDto {
  name: string;
  creatorName: string;
  creatorEmail: string;
  headRevision: number;
}
