import { ViewIcon, CopyIcon, ChevronDownIcon } from '@chakra-ui/icons';
import { Button, Wrap, WrapItem, Box, HStack, Menu, MenuButton, MenuItem, MenuList } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { FileDto } from 'dogma/features/file/FileDto';
import NextLink from 'next/link';
import { FcFile, FcOpenedFolder } from 'react-icons/fc';
import { CopySupport } from 'dogma/features/file/CopySupport';

export type FileListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  repoName: string;
  path: string;
  directoryPath: string;
  revision: string;
  copySupport: CopySupport;
};

const FileList = <Data extends object>({
  data,
  projectName,
  repoName,
  path,
  directoryPath,
  revision,
  copySupport,
}: FileListProps<Data>) => {
  const columnHelper = createColumnHelper<FileDto>();
  const slug = `/app/projects/${projectName}/repos/${repoName}/files/${revision}${path}`;
  const columns = [
    columnHelper.accessor((row: FileDto) => row.path, {
      cell: (info) => (
        <ChakraLink
          fontWeight={'semibold'}
          href={
            info.row.original.type === 'DIRECTORY'
              ? `${directoryPath}${info.getValue().slice(1)}`
              : `${slug}${info.getValue()}`
          }
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
            <NextLink
              href={
                info.row.original.type === 'DIRECTORY'
                  ? `${directoryPath}${info.getValue().slice(1)}`
                  : `${slug}${info.getValue()}`
              }
            >
              <Button leftIcon={<ViewIcon />} colorScheme="blue" size="sm">
                View
              </Button>
            </NextLink>
          </WrapItem>
          <WrapItem>
            <Menu>
              <MenuButton as={Button} size="sm" leftIcon={<CopyIcon />} rightIcon={<ChevronDownIcon />}>
                Copy
              </MenuButton>
              <MenuList>
                <MenuItem onClick={() => copySupport.handleApiUrl(projectName, repoName, info.getValue())}>
                  API URL
                </MenuItem>
                <MenuItem onClick={() => copySupport.handleWebUrl(projectName, repoName, info.getValue())}>
                  Web URL
                </MenuItem>
                <MenuItem
                  onClick={() => copySupport.handleAsCliCommand(projectName, repoName, info.getValue())}
                >
                  CLI command
                </MenuItem>
                <MenuItem
                  onClick={() => copySupport.handleAsCurlCommand(projectName, repoName, info.getValue())}
                >
                  cURL command
                </MenuItem>
              </MenuList>
            </Menu>
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
