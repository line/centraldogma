import { UserDto } from './UserDto';
import { ProjectMetadataDto } from '../project/ProjectMetadataDto';
import { ProjectRole } from './ProjectRole';

export type RepositoryRole = 'READ' | 'WRITE' | 'ADMIN';

export function findUserRepositoryRole(repoName: string, user: UserDto, metadata: ProjectMetadataDto) {
  if (user && user.systemAdmin) {
    return 'ADMIN';
  }
  const projectRole = metadata.members[user.email]?.role as ProjectRole;
  if (projectRole === 'OWNER') {
    return 'ADMIN';
  }

  const roles = metadata.repos[repoName]?.roles;
  const memberOrGuestRole = projectRole === 'MEMBER' ? roles?.projects.member : roles?.projects.guest;
  const userRepositoryRole = roles?.users[user.email];

  if (userRepositoryRole === 'ADMIN' || memberOrGuestRole === 'ADMIN') {
    return 'ADMIN';
  }

  if (userRepositoryRole === 'WRITE' || memberOrGuestRole === 'WRITE') {
    return 'WRITE';
  }

  if (userRepositoryRole === 'READ' || memberOrGuestRole === 'READ') {
    return 'READ';
  }
  return null;
}
