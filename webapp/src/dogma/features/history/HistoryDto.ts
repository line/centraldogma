export interface HistoryDto {
  revision: number;
  author: HistoryAuthorDto;
  commitMessage: HistoryDetailDto;
  pushedAt: string;
  upstreamCommitId?: string;
}

export interface HistoryAuthorDto {
  name: string;
  email: string;
}

export interface HistoryDetailDto {
  summary: string;
  detail: string;
  markup: string;
}
