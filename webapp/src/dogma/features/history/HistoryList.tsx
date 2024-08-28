import { createColumnHelper, PaginationState } from '@tanstack/react-table';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { Badge, Box, Button, HStack, Icon } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { ReactElement, useMemo } from 'react';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { Author } from 'dogma/common/components/Author';
import { GoCodescan } from 'react-icons/go';
import { VscGitCommit } from 'react-icons/vsc';
import CompareButton from 'dogma/common/components/CompareButton';

export type HistoryListProps = {
  projectName: string;
  repoName: string;
  filePath: string;
  data: HistoryDto[];
  pagination: PaginationState;
  setPagination: (updater: (old: PaginationState) => PaginationState) => void;
  pageCount: number;
  onEmptyData?: ReactElement;
  isDirectory: boolean;
};

const HistoryList = ({
  projectName,
  repoName,
  filePath,
  data,
  pagination,
  setPagination,
  pageCount,
  onEmptyData,
  isDirectory,
}: HistoryListProps) => {
  const columnHelper = createColumnHelper<HistoryDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: HistoryDto) => `${row.revision} ${row.commitMessage.summary}`, {
        cell: (info) => (
          <ChakraLink
            fontWeight="semibold"
            disabled={info.row.original.revision <= 1}
            href={`/app/projects/${projectName}/repos/${repoName}/commit/${info.row.original.revision}`}
          >
            <HStack>
              <Icon as={VscGitCommit} />
              <Box>
                <Badge colorScheme={'blue'}>{info.row.original.revision}</Badge>
              </Box>
              <Box>{info.row.original.commitMessage.summary}</Box>
            </HStack>
          </ChakraLink>
        ),
        header: 'Revision',
      }),
      columnHelper.accessor((row: HistoryDto) => row.commitMessage.detail, {
        cell: (info) => (
          <HStack>
            {isDirectory ? (
              <Button
                as={ChakraLink}
                leftIcon={<GoCodescan />}
                size={'sm'}
                colorScheme={'blue'}
                href={`/app/projects/${projectName}/repos/${repoName}/tree/${info.row.original.revision}${filePath}`}
              >
                Browse
              </Button>
            ) : null}
            <CompareButton
              projectName={projectName}
              repoName={repoName}
              headRevision={info.row.original.revision}
            />
          </HStack>
        ),
        header: 'Action',
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
    [columnHelper, projectName, repoName, filePath, isDirectory],
  );

  return (
    <DynamicDataTable
      data={data}
      columns={columns}
      setPagination={setPagination}
      pagination={pagination}
      pageCount={pageCount}
      disableGotoButton={true}
      onEmptyData={onEmptyData}
    />
  );
};

export default HistoryList;
