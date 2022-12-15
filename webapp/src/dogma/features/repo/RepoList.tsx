import { ViewIcon, DeleteIcon } from '@chakra-ui/icons';
import { Wrap, WrapItem, Button, Box, HStack, Badge, Tooltip } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { format, formatDistance } from 'date-fns';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import NextLink from 'next/link';
import { RiGitRepositoryFill } from 'react-icons/ri';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = [
    columnHelper.accessor((row: RepoDto) => row.name, {
      cell: (info) => (
        <ChakraLink
          fontWeight={'semibold'}
          href={`/app/projects/${projectName}/repos/${info.getValue()}/list/head`}
        >
          <HStack>
            <Box>
              <RiGitRepositoryFill />
            </Box>
            <Box>{info.getValue()}</Box>
          </HStack>
        </ChakraLink>
      ),
      header: 'Name',
    }),
    columnHelper.accessor((row: RepoDto) => row.creator.name, {
      cell: (info) => info.getValue(),
      header: 'Creator',
    }),
    columnHelper.accessor((row: RepoDto) => row.createdAt, {
      cell: (info) => (
        <Box>
          <Tooltip label={format(new Date(info.getValue()), 'dd MMM yyyy HH:mm z')}>
            <Badge>{formatDistance(new Date(info.getValue()), new Date(), { addSuffix: true })}</Badge>
          </Tooltip>
        </Box>
      ),
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
    columnHelper.accessor((row: RepoDto) => row.name, {
      cell: (info) => (
        <Wrap>
          <WrapItem>
            <NextLink href={`/app/projects/${projectName}/repos/${info.getValue()}/list/head`}>
              <Button leftIcon={<ViewIcon />} colorScheme="blue" size="sm">
                View
              </Button>
            </NextLink>
          </WrapItem>
          <WrapItem>
            <Button leftIcon={<DeleteIcon />} colorScheme="red" size="sm">
              Delete
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

export default RepoList;
