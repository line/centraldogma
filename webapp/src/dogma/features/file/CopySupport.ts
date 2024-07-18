export interface CopySupport {
  handleApiUrl: (project: string, repo: string, path: string) => Promise<void>;
  handleWebUrl: (project: string, repo: string, path: string) => Promise<void>;
  handleAsCliCommand: (project: string, repo: string, path: string) => Promise<void>;
  handleAsCurlCommand: (project: string, repo: string, path: string) => Promise<void>;
}
