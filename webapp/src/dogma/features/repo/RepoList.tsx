import { DeleteIcon } from '@chakra-ui/icons';
import { Wrap, WrapItem, Button, Box, HStack } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { useMemo } from 'react';
import { RiGitRepositoryFill } from 'react-icons/ri';

export type RepoListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoList = <Data extends object>({ data, projectName }: RepoListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoDto>();
  const columns = useMemo(
    () => [
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
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
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
        cell: () => (
          <Wrap>
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
    ],
    [columnHelper, projectName],
  );
  return <DynamicDataTable columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoList;