export interface CreatorDto {
  name: string;
  email: string;
}

export type ReplicationStatus = 'WRITABLE' | 'READ_ONLY';

export interface RepoDto {
  name: string;
  creator: CreatorDto;
  headRevision: number;
  url: string;
  createdAt: string;
  status: ReplicationStatus;
}
