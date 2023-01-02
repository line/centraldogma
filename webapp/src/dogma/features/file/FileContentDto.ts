export interface FileContentDto {
  content: string;
  name: string;
  path: string;
  revision: string;
  type: 'TEXT' | 'DIRECTORY' | 'JSON' | 'YAML';
}
