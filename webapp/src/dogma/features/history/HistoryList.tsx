import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { Badge, Box, Button, HStack } from '@chakra-ui/react';
import NextLink from 'next/link';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { FaHistory } from 'react-icons/fa';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useMemo } from 'react';

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
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: HistoryDto) => `${row.revision} ${row.commitMessage.summary}`, {
        cell: (info) => (
          <ChakraLink
            fontWeight="semibold"
            href={`/app/projects/${projectName}/repos/${repoName}/list/${info.row.original.revision}/`}
            onClick={() => handleTabChange(0)}
          >
            <HStack>
              <Box>
                <Badge colorScheme="blue">{info.row.original.revision}</Badge>
              </Box>
              <Box>{info.row.original.commitMessage.summary}</Box>
            </HStack>
          </ChakraLink>
        ),
        header: 'Revision',
      }),
      columnHelper.accessor((row: HistoryDto) => row.author.name, {
        cell: (info) => info.getValue(),
        header: 'Author',
      }),
      columnHelper.accessor((row: HistoryDto) => row.pushedAt, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Timestamp',
      }),
      columnHelper.accessor((row: HistoryDto) => row.revision, {
        cell: (info) => (
          <NextLink href={`/app/projects/${projectName}/repos/${repoName}/list/${info.row.original.revision}/`}>
            <Button leftIcon={<FaHistory />} size="sm" onClick={() => handleTabChange(0)}>
              View
            </Button>
          </NextLink>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, handleTabChange, projectName, repoName],
  );
  return <DynamicDataTable columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default HistoryList;
