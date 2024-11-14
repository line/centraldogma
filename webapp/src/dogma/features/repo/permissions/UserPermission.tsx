import { Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { DeleteUserPermissionDto } from 'dogma/features/repo/permissions/DeleteUserPermissionDto';
import { PerUserPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { useMemo } from 'react';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { DeleteMember } from 'dogma/features/project/settings/members/DeleteMember';

type UserAndPermission = [string, string];

export const UserPermission = ({
  projectName,
  repoName,
  perUserPermissions,
  deleteMember,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  perUserPermissions: PerUserPermissionDto;
  deleteMember: ApiAction<DeleteUserPermissionDto, void>;
  isLoading: boolean;
}) => {
  const columnHelper = createColumnHelper<UserAndPermission>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: UserAndPermission) => row[0], {
        cell: (info) => info.getValue(),
        header: 'Login ID',
      }),
      columnHelper.accessor((row: UserAndPermission) => row[1], {
        cell: (info) => (
          <Wrap>
            <WrapItem key={info.getValue()}>
              <Tag borderRadius="full" colorScheme="blue" size="sm">
                <TagLabel>{info.getValue() === 'REPO_ADMIN' ? 'ADMIN' : info.getValue()}</TagLabel>
              </Tag>
            </WrapItem>
          </Wrap>
        ),
        header: 'Permissions',
        enableSorting: false,
      }),
      columnHelper.accessor((row: UserAndPermission) => row[0], {
        cell: (info) => (
          <DeleteMember
            projectName={projectName}
            repoName={repoName}
            id={info.getValue() as unknown as string}
            deleteMember={(projectName, id, repoName) => deleteMember({ projectName, id, repoName }).unwrap()}
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteMember, isLoading, projectName, repoName],
  );
  return <DynamicDataTable columns={columns} data={Object.entries(perUserPermissions)} />;
};
