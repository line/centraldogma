export interface HistoryDto {
  revision: string;
  author: HistoryAuthorDto;
  commitMessage: HistoryDetailDto;
  pushedAt: string;
}

export interface HistoryAuthorDto {
  name: string;
  email: string;
}

export interface HistoryDetailDto {
  summary: string;
  content: string;
  markup: string;
}
