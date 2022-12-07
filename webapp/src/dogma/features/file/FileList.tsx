import { DeleteIcon, ViewIcon } from '@chakra-ui/icons';
import { Button, Link, Wrap, WrapItem } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { FileDto } from 'dogma/features/file/FileDto';
import NextLink from 'next/link';

export type FileListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  repoName: string;
};

const FileList = <Data extends object>({ data, projectName, repoName }: FileListProps<Data>) => {
  const columnHelper = createColumnHelper<FileDto>();
  const columns = [
    columnHelper.accessor((row: FileDto) => row.path, {
      cell: (info) => (
        <Link
          as={NextLink}
          href={`/app/projects/${projectName}/repos/${repoName}/files/head${info.getValue()}`}
        >
          {info.getValue()}
        </Link>
      ),
      header: 'Path',
    }),
    columnHelper.accessor((row: FileDto) => row.revision, {
      cell: (info) => info.getValue(),
      header: 'Revision',
    }),
    columnHelper.accessor((row: FileDto) => row.type, {
      cell: (info) => info.getValue(),
      header: 'Type',
    }),
    columnHelper.accessor(
      (row: FileDto) => (
        <Wrap>
          <WrapItem>
            <Link as={NextLink} href={`/app/projects/${projectName}/repos/${repoName}/files/head${row.path}`}>
              <Button leftIcon={<ViewIcon />} colorScheme="blue" size="sm">
                View
              </Button>
            </Link>
          </WrapItem>
          <WrapItem>
            <Button leftIcon={<DeleteIcon />} colorScheme="red" size="sm">
              Delete
            </Button>
          </WrapItem>
        </Wrap>
      ),
      {
        cell: (info) => info.getValue(),
        header: 'Actions',
        enableSorting: false,
      },
    ),
  ];
  return <DynamicDataTable columns={columns as ColumnDef<Data, any>[]} data={data} />;
};

export default FileList;
