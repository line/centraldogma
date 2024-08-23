export type FileType = 'TEXT' | 'DIRECTORY' | 'JSON' | 'YML';

export interface FileDto {
  path: string;
  revision: number;
  type: FileType;
  url: string;
  content?: string | any;
}
