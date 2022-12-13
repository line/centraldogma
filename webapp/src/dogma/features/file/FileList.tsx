import { EditIcon, ViewIcon } from '@chakra-ui/icons';
import { Button, Wrap, WrapItem, Box, HStack } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { FileDto } from 'dogma/features/file/FileDto';
import NextLink from 'next/link';
import { FcFile, FcOpenedFolder } from 'react-icons/fc';

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
          <HStack>
            <Box>{info.row.original.type === 'DIRECTORY' ? <FcOpenedFolder /> : <FcFile />}</Box>
            <Box>{info.getValue()}</Box>
          </HStack>
        </ChakraLink>
      ),
      header: 'Path',
    }),
    columnHelper.accessor((row: FileDto) => row.revision, {
      cell: (info) => info.getValue(),
      header: 'Revision',
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
          <WrapItem>
            <Button leftIcon={<EditIcon />} colorScheme="gray" size="sm">
              Edit
            </Button>
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
