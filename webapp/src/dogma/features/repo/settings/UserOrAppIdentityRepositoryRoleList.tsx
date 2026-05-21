import { Tag, TagLabel, Tooltip, Wrap, WrapItem } from '@chakra-ui/react';
import { WarningIcon } from '@chakra-ui/icons';
import { createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { DeleteUserOrAppIdentityRepositoryRoleDto } from 'dogma/features/repo/settings/DeleteUserOrAppIdentityRepositoryRoleDto';
import {
  ProjectRolesDto,
  UserOrAppIdentityRepositoryRoleDto,
} from 'dogma/features/repo/RepositoriesMetadataDto';
import { RepositoryRole } from 'dogma/features/auth/RepositoryRole';
import { useMemo } from 'react';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { DeleteAppEntity } from 'dogma/features/project/settings/DeleteAppEntity';
import { AppMemberDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { AppIdDto } from 'dogma/features/project/settings/app-identities/AppIdDto';

type UserAndRole = [string, string];

const ROLE_PRIORITY: Record<string, number> = {
  READ: 1,
  WRITE: 2,
  ADMIN: 3,
};

function getEffectiveProjectRole(
  id: string,
  entityType: 'user' | 'appIdentity',
  projectRoles: ProjectRolesDto | undefined,
  members: AppMemberDto | undefined,
  appIds: AppIdDto | undefined,
): RepositoryRole | null {
  if (!projectRoles) {
    return null;
  }

  if (entityType === 'user') {
    const member = members?.[id];
    if (!member) {
      return projectRoles.guest;
    }
    if (member.role === 'OWNER') {
      return 'ADMIN';
    }
    return projectRoles.member;
  } else {
    const appId = appIds?.[id];
    if (!appId) {
      return projectRoles.guest;
    }
    if (appId.role === 'OWNER') {
      return 'ADMIN';
    }
    return projectRoles.member;
  }
}

type UserOrAppIdentityRepositoryRoleListProps = {
  projectName: string;
  repoName: string;
  entityType: 'appIdentity' | 'user';
  userOrAppIdentityRepositoryRole: UserOrAppIdentityRepositoryRoleDto;
  deleteUserOrAppIdentity: ApiAction<DeleteUserOrAppIdentityRepositoryRoleDto, void>;
  isLoading: boolean;
  projectRoles?: ProjectRolesDto;
  members?: AppMemberDto;
  appIds?: AppIdDto;
};

export const UserOrAppIdentityRepositoryRoleList = ({
  projectName,
  repoName,
  entityType,
  userOrAppIdentityRepositoryRole,
  deleteUserOrAppIdentity,
  isLoading,
  projectRoles,
  members,
  appIds,
}: UserOrAppIdentityRepositoryRoleListProps): JSX.Element => {
  const columnHelper = createColumnHelper<UserAndRole>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: UserAndRole) => row[0], {
        cell: (info) => info.getValue(),
        header: entityType === 'user' ? 'Login ID' : 'App ID',
      }),
      columnHelper.accessor((row: UserAndRole) => row[1], {
        cell: (info) => {
          const id = info.row.original[0];
          const repoRole = info.getValue() as RepositoryRole;
          const effectiveProjectRole = getEffectiveProjectRole(id, entityType, projectRoles, members, appIds);
          const isOverridden =
            effectiveProjectRole != null && ROLE_PRIORITY[effectiveProjectRole] > ROLE_PRIORITY[repoRole];
          return (
            <Wrap align="center">
              <WrapItem key={repoRole}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{repoRole}</TagLabel>
                </Tag>
              </WrapItem>
              {isOverridden && (
                <WrapItem>
                  <Tooltip
                    label={`The effective role is ${effectiveProjectRole} because the project-level role grants higher access.`}
                  >
                    <Tag borderRadius="full" colorScheme="yellow" size="sm">
                      <WarningIcon mr={1} />
                      <TagLabel>Effective: {effectiveProjectRole}</TagLabel>
                    </Tag>
                  </Tooltip>
                </WrapItem>
              )}
            </Wrap>
          );
        },
        header: 'Roles',
        enableSorting: false,
      }),
      columnHelper.accessor((row: UserAndRole) => row[0], {
        cell: (info) => (
          <DeleteAppEntity
            projectName={projectName}
            repoName={repoName}
            id={info.getValue() as unknown as string}
            entityType={entityType}
            deleteEntity={(projectName, id, repoName) =>
              deleteUserOrAppIdentity({ projectName, id, repoName }).unwrap()
            }
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [
      columnHelper,
      deleteUserOrAppIdentity,
      isLoading,
      projectName,
      repoName,
      entityType,
      projectRoles,
      members,
      appIds,
    ],
  );
  return <DynamicDataTable columns={columns} data={Object.entries(userOrAppIdentityRepositoryRole)} />;
};
