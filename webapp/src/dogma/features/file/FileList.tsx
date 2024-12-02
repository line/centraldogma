import { CopyIcon, ChevronDownIcon } from '@chakra-ui/icons';
import {
  Button,
  Wrap,
  WrapItem,
  Box,
  HStack,
  Menu,
  MenuButton,
  MenuItem,
  MenuList,
  useDisclosure,
} from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { FileDto } from 'dogma/features/file/FileDto';
import { FcFile, FcOpenedFolder } from 'react-icons/fc';
import { CopySupport } from 'dogma/features/file/CopySupport';
import React, { useCallback, useMemo, useState } from 'react';
import { MdDelete } from 'react-icons/md';
import { DeleteFileModal } from 'dogma/common/components/editor/DeleteFileModal';

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

  const { isOpen: isDeleteModalOpen, onOpen: onDeleteModalOpen, onClose: onDeleteModalClose } = useDisclosure();
  const [deletePath, setDeletePath] = useState('');
  const onClickDelete = useCallback(
    (path: string) => {
      setDeletePath(path);
      onDeleteModalOpen();
    },
    [setDeletePath, onDeleteModalOpen],
  );

  const columns = useMemo(
    () => [
      columnHelper.accessor((row: FileDto) => row.path.split('/').pop(), {
        cell: (info) => (
          <ChakraLink
            fontWeight={'semibold'}
            href={
              info.row.original.type === 'DIRECTORY'
                ? `${directoryPath}/${info.getValue()}`
                : `${slug}/${info.getValue()}`
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
          <HStack>
            <Wrap>
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
            <Button
              onClick={() => onClickDelete(info.row.original.path)}
              leftIcon={<MdDelete />}
              colorScheme="red"
              size="sm"
            >
              Delete
            </Button>
          </HStack>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, copySupport, directoryPath, projectName, repoName, slug, onClickDelete],
  );
  return (
    <Box>
      <DynamicDataTable key={slug} columns={columns as ColumnDef<Data>[]} data={data} />
      <DeleteFileModal
        isOpen={isDeleteModalOpen}
        onClose={onDeleteModalClose}
        path={deletePath}
        projectName={projectName}
        repoName={repoName}
        onSuccess={() => {
          setDeletePath('');
          onDeleteModalClose();
        }}
      />
    </Box>
  );
};

export default FileList;
