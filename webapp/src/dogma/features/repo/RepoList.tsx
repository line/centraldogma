import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { useMemo } from 'react';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoDto) => row.name, {
        cell: (info) => (
          <ChakraLink
            fontWeight={'semibold'}
            href={`/app/projects/${projectName}/repos/${info.getValue()}/list/head`}
          >
            {info.getValue()}
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoDto) => row.creator.name, {
        cell: (info) => info.getValue(),
        header: 'Creator',
      }),
      columnHelper.accessor((row: RepoDto) => row.createdAt, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: RepoDto) => row.headRevision, {
        // TODO: Show the commit message of HEAD revision
        cell: (info) => info.getValue(),
        header: 'HEAD',
        meta: {
          isNumeric: true,
        },
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoList;
