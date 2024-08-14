import { useAppSelector } from 'dogma/hooks';
import { useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import { ReactNode } from 'react';
import { UserDto } from './UserDto';
import { AppMemberDto } from '../project/settings/members/AppMemberDto';
import { ProjectMetadataDto } from '../project/ProjectMetadataDto';

type ProjectRole = 'OWNER' | 'MEMBER' | 'GUEST' | 'ANONYMOUS';

type WithProjectRoleProps = {
  projectName: string;
  roles: ProjectRole[];
  children: () => ReactNode;
};

export function findUserRole(user: UserDto, metadata: ProjectMetadataDto) {
  let role: ProjectRole;
  if (metadata && user) {
    if (user.admin) {
      role = 'OWNER';
    } else {
      role = metadata.members[user.email]?.role as ProjectRole;
    }
  }
  if (!role) {
    role = 'GUEST';
  }
  return role;
}

export const WithProjectRole = ({ projectName, roles, children }: WithProjectRoleProps) => {
  const { data: metadata } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
  });

  const { user } = useAppSelector((state) => state.auth);
  const role = findUserRole(user, metadata);

  if (roles.find((r) => r === role)) {
    return <>{children()}</>;
  } else {
    return null;
  }
};
