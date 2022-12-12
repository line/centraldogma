import { ViewIcon } from '@chakra-ui/icons';
import { Button, Wrap, WrapItem } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
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
        <ChakraLink
          fontWeight={'semibold'}
          href={`/app/projects/${projectName}/repos/${repoName}/files/head${info.getValue()}`}
        >
          {info.getValue()}
        </ChakraLink>
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
    columnHelper.accessor((row: FileDto) => row.path, {
      cell: (info) => (
        <Wrap>
          <WrapItem>
            <NextLink href={`/app/projects/${projectName}/repos/${repoName}/files/head${info.getValue()}`}>
              <Button leftIcon={<ViewIcon />} colorScheme="blue" size="sm">
                View
              </Button>
            </NextLink>
          </WrapItem>
        </Wrap>
      ),
      header: 'Actions',
      enableSorting: false,
    }),
  ];
  return <DynamicDataTable columns={columns as ColumnDef<Data, any>[]} data={data} />;
};

export default FileList;
