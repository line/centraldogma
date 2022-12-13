import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { formatDistance } from 'date-fns';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { ViewIcon } from '@chakra-ui/icons';
import { Button } from '@chakra-ui/react';
import NextLink from 'next/link';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

export type HistoryListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  repoName: string;
  handleTabChange: Function;
};

const HistoryList = <Data extends object>({
  data,
  projectName,
  repoName,
  handleTabChange,
}: HistoryListProps<Data>) => {
  const columnHelper = createColumnHelper<HistoryDto>();
  const columns = [
    columnHelper.accessor((row: HistoryDto) => parseInt(row.revision.revisionNumber), {
      cell: (info) => (
        <ChakraLink
          fontWeight="semibold"
          href={`/app/projects/${projectName}/repos/${repoName}/list/${info.getValue()}/`}
          onClick={() => handleTabChange(0)}
        >
          {info.getValue()}
        </ChakraLink>
      ),
      header: 'Revision',
    }),
    columnHelper.accessor((row: HistoryDto) => row.summary, {
      cell: (info) => info.getValue(),
      header: 'Summary',
    }),

    columnHelper.accessor((row: HistoryDto) => row.author.name, {
      cell: (info) => info.getValue(),
      header: 'Author',
    }),
    columnHelper.accessor((row: HistoryDto) => row.timestamp, {
      cell: (info) => formatDistance(new Date(info.getValue()), new Date(), { addSuffix: true }),
      header: 'Timestamp',
    }),
    columnHelper.accessor((row: HistoryDto) => row.revision.revisionNumber, {
      cell: (info) => (
        <NextLink href={`/app/projects/${projectName}/repos/${repoName}/list/${info.getValue()}/`}>
          <Button leftIcon={<ViewIcon />} colorScheme="blue" size="sm" onClick={() => handleTabChange(0)}>
            View
          </Button>
        </NextLink>
      ),
      header: 'Actions',
      enableSorting: false,
    }),
  ];
  return <DynamicDataTable columns={columns as ColumnDef<Data, any>[]} data={data} />;
};

export default HistoryList;
