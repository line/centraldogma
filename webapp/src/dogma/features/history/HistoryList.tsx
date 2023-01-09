import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { Box, Button, HStack, Tag } from '@chakra-ui/react';
import NextLink from 'next/link';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { FaHistory } from 'react-icons/fa';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';

export type HistoryListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  repoName: string;
  handleTabChange: (index: number) => void;
};

const HistoryList = <Data extends object>({
  data,
  projectName,
  repoName,
  handleTabChange,
}: HistoryListProps<Data>) => {
  const columnHelper = createColumnHelper<HistoryDto>();
  const columns = [
    columnHelper.accessor((row: HistoryDto) => `${row.revision.revisionNumber} ${row.summary}`, {
      cell: (info) => (
        <ChakraLink
          fontWeight="semibold"
          href={`/app/projects/${projectName}/repos/${repoName}/list/${info.row.original.revision.revisionNumber}/`}
          onClick={() => handleTabChange(0)}
        >
          <HStack>
            <Box>
              <Tag colorScheme="blue">{info.row.original.revision.revisionNumber}</Tag>
            </Box>
            <Box>{info.row.original.summary}</Box>
          </HStack>
        </ChakraLink>
      ),
      header: 'Revision',
    }),
    columnHelper.accessor((row: HistoryDto) => row.author.name, {
      cell: (info) => info.getValue(),
      header: 'Author',
    }),
    columnHelper.accessor((row: HistoryDto) => row.timestamp, {
      cell: (info) => <DateWithTooltip date={info.getValue()} />,
      header: 'Timestamp',
    }),
    columnHelper.accessor((row: HistoryDto) => row.revision.revisionNumber, {
      cell: (info) => (
        <NextLink
          href={`/app/projects/${projectName}/repos/${repoName}/list/${info.row.original.revision.revisionNumber}/`}
        >
          <Button leftIcon={<FaHistory />} size="sm" onClick={() => handleTabChange(0)}>
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
