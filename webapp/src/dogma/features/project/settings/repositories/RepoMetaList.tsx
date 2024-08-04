import { Badge, Wrap } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { DeleteRepo } from 'dogma/features/repo/DeleteRepo';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { RestoreRepo } from 'dogma/features/repo/RestoreRepo';
import { useMemo } from 'react';
import { RepoIcon } from 'dogma/common/components/RepoIcon';

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
          return (
            <RepoIcon
              projectName={projectName}
              repoName={info.getValue()}
              isActive={info.row.original.removal == null}
            />
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
