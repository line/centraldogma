export interface FileContentDto {
  content?: string;
  rawContent?: string;
  name: string;
  path: string;
  revision: string;
  type: 'TEXT' | 'DIRECTORY' | 'JSON' | 'YAML';
}
