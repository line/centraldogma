import { useAppSelector } from 'dogma/hooks';
import { useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import { ReactNode } from 'react';

type ProjectRole = 'OWNER' | 'MEMBER' | 'GUEST' | 'ANONYMOUS';

type WithProjectRoleProps = {
  projectName: string;
  roles: ProjectRole[];
  children: () => ReactNode;
};

export const WithProjectRole = ({ projectName, roles, children }: WithProjectRoleProps) => {
  const { data: metadata } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
  });

  let role: ProjectRole;
  const { user } = useAppSelector((state) => state.auth);
  if (metadata && user) {
    role = metadata.members[user.email]?.role as ProjectRole;
  }
  if (!role) {
    role = 'GUEST';
  }

  if (roles.find((r) => r === role)) {
    return <>{children()}</>;
  } else {
    return null;
  }
};
