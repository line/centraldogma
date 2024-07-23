import { useAppSelector } from 'dogma/hooks';
import { AppMemberDetailDto } from 'dogma/features/project/settings/members/AppMemberDto';
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

  let role: ProjectRole = 'GUEST';
  const { user } = useAppSelector((state) => state.auth);
  if (metadata && user) {
    const appUser = Array.from(Object.values(metadata.members)).find(
      (m: AppMemberDetailDto) => m.login === user.email,
    );
    if (appUser != null) {
      role = appUser.role;
    }
  }

  if (roles.find((r) => r === role)) {
    return <>{children()}</>;
  } else {
    return null;
  }
};
