import { Tag, WrapItem, Wrap, TagLabel } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { useMemo } from 'react';
import { AppMemberDetailDto } from 'dogma/features/metadata/AppMemberDto';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

export type RepoPermissionListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  members: AppMemberDetailDto[];
};

const RepoPermissionList = <Data extends object>({ data, projectName }: RepoPermissionListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoPermissionDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) => (
          <ChakraLink fontWeight={'semibold'} href={`/app/projects/metadata/${projectName}/${info.getValue()}`}>
            {info.getValue()}
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.owner, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Owner',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.member, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Member',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.guest, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Guest',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoPermissionList;
