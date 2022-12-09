import { ViewIcon, DeleteIcon } from '@chakra-ui/icons';
import { Wrap, WrapItem, Button } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { formatDistance } from 'date-fns';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import NextLink from 'next/link';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = [
    columnHelper.accessor((row: RepoDto) => row.name, {
      cell: (info) => (
        <ChakraLink href={`/app/projects/${projectName}/repos/${info.getValue()}`}>
          {info.getValue()}
        </ChakraLink>
      ),
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
    columnHelper.accessor((row: RepoDto) => row.name, {
      cell: (info) => (
        <Wrap>
          <WrapItem>
            <NextLink href={`/app/projects/${projectName}/repos/${info.getValue()}s`}>
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
