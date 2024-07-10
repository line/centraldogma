import { PaginationState, createColumnHelper } from '@tanstack/react-table';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { Badge, Box, HStack, Text } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useMemo, useState } from 'react';
import { useGetHistoryQuery } from 'dogma/features/api/apiSlice';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { Deferred } from 'dogma/common/components/Deferred';
import { Author } from 'dogma/common/components/Author';

export type HistoryListProps = {
  projectName: string;
  repoName: string;
  handleTabChange: (index: number) => void;
  totalRevision: number;
};

const HistoryList = ({ projectName, repoName, handleTabChange, totalRevision }: HistoryListProps) => {
  const columnHelper = createColumnHelper<HistoryDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: HistoryDto) => `${row.revision} ${row.commitMessage.summary}`, {
        cell: (info) => (
          <ChakraLink
            fontWeight="semibold"
            href={`/app/projects/${projectName}/repos/${repoName}/tree/${info.row.original.revision}/`}
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
      columnHelper.accessor((row: HistoryDto) => row.commitMessage.detail, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Detail',
      }),
      columnHelper.accessor((row: HistoryDto) => row.author.name, {
        cell: (info) => <Author name={info.getValue()} />,
        header: 'Author',
      }),
      columnHelper.accessor((row: HistoryDto) => row.pushedAt, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Timestamp',
      }),
    ],
    [columnHelper, handleTabChange, projectName, repoName],
  );
  const [{ pageIndex, pageSize }, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  const pagination = useMemo(
    () => ({
      pageIndex,
      pageSize,
    }),
    [pageIndex, pageSize],
  );
  const { data, isLoading, error } = useGetHistoryQuery({
    projectName,
    repoName,
    //  revision starts from -1, for example for pageSize=20
    //  The first page  /projects/{projectName}/repos/{repoName}/commits/-1?to=-20
    //  The second page /projects/{projectName}/repos/{repoName}/commits/-20?to=-40
    revision: -pageIndex * pageSize - 1,
    to: Math.max(-totalRevision, -(pageIndex + 1) * pageSize),
  });
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <DynamicDataTable
          data={data || []}
          columns={columns}
          setPagination={setPagination}
          pagination={pagination}
          pageCount={Math.ceil(totalRevision / pageSize)}
        />
      )}
    </Deferred>
  );
};

export default HistoryList;
