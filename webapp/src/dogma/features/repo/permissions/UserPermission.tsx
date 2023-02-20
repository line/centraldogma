import { Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import {
  MutationDefinition,
  BaseQueryFn,
  FetchArgs,
  FetchBaseQueryError,
  FetchBaseQueryMeta,
} from '@reduxjs/toolkit/dist/query';
import { MutationTrigger } from '@reduxjs/toolkit/dist/query/react/buildHooks';
import { createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { DeleteMember } from 'dogma/features/metadata/DeleteMember';
import { DeleteUserPermissionDto } from 'dogma/features/repo/permissions/DeleteUserPermissionDto';
import { PerUserPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { useMemo } from 'react';

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
  deleteMember: MutationTrigger<
    MutationDefinition<
      DeleteUserPermissionDto,
      BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError, Record<string, never>, FetchBaseQueryMeta>,
      'Metadata',
      void,
      'api'
    >
  >;
  isLoading: boolean;
}) => {
  const columnHelper = createColumnHelper<[string, string[]]>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row) => row[0], {
        cell: (info) => info.getValue(),
        header: 'Login ID',
      }),
      columnHelper.accessor((row) => row[1], {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission as string}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Permissions',
        enableSorting: false,
      }),
      columnHelper.accessor((row) => row[0], {
        cell: (info) => (
          <DeleteMember
            projectName={projectName}
            repoName={repoName}
            id={info.getValue() as unknown as string}
            deleteMember={deleteMember}
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
