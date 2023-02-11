import { Wrap, Box, Text, Badge } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DeleteRepo } from 'dogma/features/repo/DeleteRepo';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { RestoreRepo } from 'dogma/features/repo/RestoreRepo';
import { useMemo } from 'react';
import { DataTableClientPagination } from '../../common/components/table/DataTableClientPagination';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoMetaList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoPermissionDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) =>
          info.row.original.removal ? (
            <Text>{info.getValue()}</Text>
          ) : (
            <ChakraLink
              fontWeight={'semibold'}
              href={`/app/projects/${projectName}/repos/${info.getValue()}/list/head`}
            >
              <Box>{info.getValue()}</Box>
            </ChakraLink>
          ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.creation.user, {
        cell: (info) => info.getValue(),
        header: 'Creator',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      // TODO: Show the commit message of HEAD revision
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.removal, {
        cell: (info) => (
          <Badge colorScheme={info.getValue() ? 'gray' : 'blue'}>
            {info.getValue() ? 'Inactive' : 'Active'}
          </Badge>
        ),
        header: 'Status',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) => (
          <Wrap>
            <DeleteRepo
              projectName={projectName}
              repoName={info.getValue()}
              hidden={info.row.original.removal !== undefined}
            />
            <RestoreRepo
              projectName={projectName}
              repoName={info.getValue()}
              hidden={info.row.original.removal === undefined}
            />
          </Wrap>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoMetaList;
