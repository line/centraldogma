export interface HistoryDto {
  revision: HistoryRevisionDto;
  author: HistoryAuthorDto;
  timestamp: string;
  summary: string;
  detail: HistoryDetailDto;
  diffs: string[];
}

export interface HistoryRevisionDto {
  major: number;
  minor: number;
  revisionNumber: string;
}

export interface HistoryAuthorDto {
  name: string;
  email: string;
}

export interface HistoryDetailDto {
  content: string;
  markup: string;
}
