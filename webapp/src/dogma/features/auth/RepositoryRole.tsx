import { UserDto } from './UserDto';
import { ProjectMetadataDto } from '../project/ProjectMetadataDto';
import { ProjectRole } from './ProjectRole';
import { useGetMetadataByProjectNameQuery } from '../api/apiSlice';
import { useAppSelector } from '../../hooks';
import { ReactNode } from 'react';

export type RepositoryRole = 'READ' | 'WRITE' | 'ADMIN';

type WithRepositoryRoleProps = {
  projectName: string;
  repoName: string;
  roles: RepositoryRole[];
  children: () => ReactNode;
};

export function findUserRepositoryRole(repoName: string, user: UserDto, metadata: ProjectMetadataDto) {
  if (user && user.systemAdmin) {
    return 'ADMIN';
  }
  if (typeof metadata === 'undefined' || metadata === null || Object.keys(metadata).length == 0) {
    return null;
  }

  const projectRole = metadata.members[user.email]?.role as ProjectRole;
  if (projectRole === 'OWNER') {
    return 'ADMIN';
  }

  const roles = metadata.repos[repoName]?.roles;
  const memberRole = projectRole === 'MEMBER' ? roles?.projects.member : null;
  const userRepositoryRole = roles?.users[user.email];

  if (userRepositoryRole === 'ADMIN' || memberRole === 'ADMIN') {
    return 'ADMIN';
  }

  if (userRepositoryRole === 'WRITE' || memberRole === 'WRITE') {
    return 'WRITE';
  }

  if (userRepositoryRole === 'READ' || memberRole === 'READ' || roles?.projects.guest === 'READ') {
    return 'READ';
  }
  return null;
}

export const WithRepositoryRole = ({ projectName, repoName, roles, children }: WithRepositoryRoleProps) => {
  const { data: metadata = {} as ProjectMetadataDto } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
  });

  const { user, isInAnonymousMode } = useAppSelector((state) => state.auth);
  if (isInAnonymousMode) {
    return <>{children()}</>;
  }

  const role = findUserRepositoryRole(repoName, user, metadata);

  if (roles.find((r) => r === role)) {
    return <>{children()}</>;
  } else {
    return null;
  }
};
