import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { formatDistance } from 'date-fns';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoDto } from 'dogma/features/repo/RepoDto';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
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
  return (
    <DynamicDataTable
      columns={columns as ColumnDef<Data, any>[]}
      data={data}
      urlPrefix={`/app/projects/${projectName}/repos/`}
    />
  );
};

export default RepoList;
