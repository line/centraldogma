import { Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { DeleteUserOrTokenRepositoryRoleDto } from 'dogma/features/repo/settings/DeleteUserOrTokenRepositoryRoleDto';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { useMemo } from 'react';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { DeleteAppEntity } from 'dogma/features/project/settings/DeleteAppEntity';

type UserAndRole = [string, string];

type UserOrTokenRepositoryRoleListProps = {
  projectName: string;
  repoName: string;
  entityType: 'token' | 'user';
  userOrTokenRepositoryRole: UserOrTokenRepositoryRoleDto;
  deleteUserOrToken: ApiAction<DeleteUserOrTokenRepositoryRoleDto, void>;
  isLoading: boolean;
};

export const UserOrTokenRepositoryRoleList = ({
  projectName,
  repoName,
  entityType,
  userOrTokenRepositoryRole,
  deleteUserOrToken,
  isLoading,
}: UserOrTokenRepositoryRoleListProps): JSX.Element => {
  const columnHelper = createColumnHelper<UserAndRole>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: UserAndRole) => row[0], {
        cell: (info) => info.getValue(),
        header: entityType === 'user' ? 'Login ID' : 'App ID',
      }),
      columnHelper.accessor((row: UserAndRole) => row[1], {
        cell: (info) => (
          <Wrap>
            <WrapItem key={info.getValue()}>
              <Tag borderRadius="full" colorScheme="blue" size="sm">
                <TagLabel>{info.getValue()}</TagLabel>
              </Tag>
            </WrapItem>
          </Wrap>
        ),
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
              deleteUserOrToken({ projectName, id, repoName }).unwrap()
            }
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteUserOrToken, isLoading, projectName, repoName, entityType],
  );
  return <DynamicDataTable columns={columns} data={Object.entries(userOrTokenRepositoryRole)} />;
};
