import { RepoDataTableDto, RepoDto } from '@/dogma/features/repository/RepoDto';
import { useRouter } from 'next/router';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/dogma/common/components/DataTable';

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
      header: 'HEAD Revision',
      meta: {
        isNumeric: true,
      },
    }),
  ];
  const router = useRouter();
  const navigate = (row: RepoDataTableDto) => {
    router.push(`${name}/repos/${row.name}`);
  };

  return <DataTable columns={columns as ColumnDef<Data, any>[]} data={data} handleOnClick={navigate} />;
};

export default RepositoryList;
