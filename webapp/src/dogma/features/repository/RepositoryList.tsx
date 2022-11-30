import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTable } from 'dogma/common/components/table/DataTable';
import { RepoDto } from 'dogma/features/repository/RepoDto';

export type RepositoryListProps<Data extends object> = {
  data: Data[];
  name: string;
};

const RepositoryList = <Data extends object>({ data, name }: RepositoryListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = [
    columnHelper.accessor('name', {
      cell: (info) => info.getValue(),
      header: 'Name',
    }),
    columnHelper.accessor('creator.name', {
      cell: (info) => info.getValue(),
      header: 'Creator',
    }),
    columnHelper.accessor('creator.email', {
      cell: (info) => info.getValue(),
      header: 'Email',
    }),
    columnHelper.accessor('headRevision', {
      cell: (info) => info.getValue(),
      header: 'HEAD',
      meta: {
        isNumeric: true,
      },
    }),
  ];
  return <DataTable columns={columns as ColumnDef<Data, any>[]} data={data} name={name} />;
};

export default RepositoryList;
