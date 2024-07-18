export interface FileDto {
  path: string;
  revision: number;
  type: 'TEXT' | 'DIRECTORY' | 'JSON' | 'YML';
  url: string;
}
