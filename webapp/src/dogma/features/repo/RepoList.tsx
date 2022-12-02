import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { formatDistance } from 'date-fns';
import { DataTable } from 'dogma/common/components/table/DataTable';
import { RepoDto } from 'dogma/features/repo/RepoDto';

export type RepoListProps<Data extends object> = {
  data: Data[];
  name: string;
};

const RepoList = <Data extends object>({ data, name }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = [
    columnHelper.accessor((row: RepoDto) => row.name, {
      cell: (info) => info.getValue(),
      header: 'Name',
    }),
    columnHelper.accessor((row: RepoDto) => row.creator.name, {
      cell: (info) => info.getValue(),
      header: 'Creator',
    }),
    columnHelper.accessor((row: RepoDto) => row.createdAt, {
      cell: (info) => formatDistance(new Date(info.getValue()), new Date(), { addSuffix: true }),
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
  ];
  return <DataTable columns={columns as ColumnDef<Data, any>[]} data={data} urlPrefix={name + '/repos'} />;
};

export default RepoList;
