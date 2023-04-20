import { Badge, Icon, Wrap } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { DeleteRepo } from 'dogma/features/repo/DeleteRepo';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { RestoreRepo } from 'dogma/features/repo/RestoreRepo';
import { useMemo } from 'react';
import { GoRepo } from 'react-icons/go';
import { FiArchive } from 'react-icons/fi';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoMetaList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoPermissionDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) => {
          return info.row.original.removal ? (
            <>
              <Icon as={FiArchive} marginBottom={-0.5} /> {info.getValue()}
            </>
          ) : (
            <ChakraLink
              fontWeight={'semibold'}
              href={`/app/projects/${projectName}/repos/${info.getValue()}/list/head`}
            >
              <Icon as={GoRepo} marginBottom={-0.5} /> {info.getValue()}
            </ChakraLink>
          );
        },
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.removal, {
        cell: (info) => {
          return info.getValue() ? <Badge>Inactive</Badge> : <Badge colorScheme="blue">Active</Badge>;
        },
        header: 'Status',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.creation.user, {
        cell: (info) => info.getValue(),
        header: 'Creator',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) =>
          info.getValue() !== 'meta' && (
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
