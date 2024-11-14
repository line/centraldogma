import { Icon, Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { useMemo } from 'react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { RiGitRepositoryPrivateLine } from 'react-icons/ri';

export type RepoPermissionListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoPermissionList = <Data extends object>({ data, projectName }: RepoPermissionListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoPermissionDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) => (
          <ChakraLink
            fontWeight={'semibold'}
            href={`/app/projects/${projectName}/repos/${info.getValue()}/permissions`}
          >
            <Icon as={RiGitRepositoryPrivateLine} marginBottom={-0.5} /> {info.getValue()}
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.member, {
        cell: (info) => (
          <Wrap>
            {info.getValue() !== null && (
              <WrapItem key={info.getValue()}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{info.getValue() === 'REPO_ADMIN' ? 'ADMIN' : info.getValue()}</TagLabel>
                </Tag>
              </WrapItem>
            )}
          </Wrap>
        ),
        header: 'Member',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.guest, {
        cell: (info) => (
          <Wrap>
            {info.getValue() !== null && (
              <WrapItem key={info.getValue()}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{info.getValue()}</TagLabel>
                </Tag>
              </WrapItem>
            )}
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
