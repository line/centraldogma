export type FileType = 'TEXT' | 'DIRECTORY' | 'JSON' | 'YML';

export interface FileDto {
  path: string;
  revision: number;
  type: FileType;
  url: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  content?: string | any;
}
