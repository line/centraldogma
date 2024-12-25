import { Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { DeleteUserRepositoryRoleDto } from 'dogma/features/repo/roles/DeleteUserRepositoryRoleDto';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { useMemo } from 'react';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { DeleteMember } from 'dogma/features/project/settings/members/DeleteMember';

type UserAndRole = [string, string];

export const UserRepositoryRole = ({
  projectName,
  repoName,
  userOrTokenRepositoryRole,
  deleteMember,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  userOrTokenRepositoryRole: UserOrTokenRepositoryRoleDto;
  deleteMember: ApiAction<DeleteUserRepositoryRoleDto, void>;
  isLoading: boolean;
}) => {
  const columnHelper = createColumnHelper<UserAndRole>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: UserAndRole) => row[0], {
        cell: (info) => info.getValue(),
        header: 'Login ID',
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
  return <DynamicDataTable columns={columns} data={Object.entries(userOrTokenRepositoryRole)} />;
};
