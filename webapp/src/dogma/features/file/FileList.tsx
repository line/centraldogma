import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { FileDto } from 'dogma/features/file/FileDto';

export type FileListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  repoName: string;
};

const FileList = <Data extends object>({ data, projectName, repoName }: FileListProps<Data>) => {
  const columnHelper = createColumnHelper<FileDto>();
  const columns = [
    columnHelper.accessor('path', {
      cell: (info) => info.getValue(),
      header: 'Path',
    }),
    columnHelper.accessor('revision', {
      cell: (info) => info.getValue(),
      header: 'Revision',
    }),
    columnHelper.accessor('type', {
      cell: (info) => info.getValue(),
      header: 'Type',
    }),
  ];
  return (
    <DynamicDataTable
      columns={columns as ColumnDef<Data, any>[]}
      data={data}
      urlPrefix={`/app/projects/${projectName}/repos/${repoName}/files/head`}
    />
  );
};

export default FileList;
