export function isInternalRepo(repo: string): boolean {
  return repo === 'meta' || repo === 'dogma';
}

export function isInternalProject(project: string): boolean {
  return project === 'dogma' || project.startsWith('@');
}
