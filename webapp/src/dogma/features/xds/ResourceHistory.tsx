/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import {
  Alert,
  AlertIcon,
  Badge,
  Box,
  Button,
  Divider,
  Flex,
  Heading,
  HStack,
  Icon,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalHeader,
  ModalOverlay,
  Select,
  Spacer,
  Text,
  useDisclosure,
} from '@chakra-ui/react';
import {
  ColumnDef,
  createColumnHelper,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useCallback, useMemo, useState } from 'react';
import { VscGitCommit } from 'react-icons/vsc';
import { DataTable } from 'dogma/features/xds/DataTable';
import { Deferred } from 'dogma/common/components/Deferred';
import { Loading } from 'dogma/common/components/Loading';
import { Author } from 'dogma/common/components/Author';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { JsonDiffEditor } from 'dogma/common/components/JsonDiffEditor';
import { HistoryDto } from 'dogma/features/history/HistoryDto';
import { FileDto } from 'dogma/features/file/FileDto';
import { useGetGroupHistoryQuery } from 'dogma/features/xds/xdsApiSlice';
import { useGetFileContentQuery, useGetFilesQuery } from 'dogma/features/api/apiSlice';
import { XDS_PROJECT } from 'dogma/features/xds/XdsTypes';

const columnHelper = createColumnHelper<HistoryDto>();

// The most recent commits to show. Each xDS resource create/update/delete is one commit.
const MAX_COMMITS = 100;

// Re-serializes JSON content so the before/after sides of the diff have identical formatting and only real
// changes stand out. Returns '' when the file is absent at that revision (a create or delete).
function prettyContent(content: string | undefined, rawContent: string | undefined): string {
  const raw = content ?? rawContent;
  if (raw == null) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

// Shows the before/after diff of all files changed in a group-wide commit, by comparing the full repo snapshot
// at `revision` vs `revision - 1`. Each changed file is shown as a separate Monaco diff panel.
const GroupCommitDiffModal = ({
  group,
  revision,
  summary,
  isOpen,
  onClose,
}: {
  group: string;
  revision: number;
  summary: string;
  isOpen: boolean;
  onClose: () => void;
}) => {
  const after = useGetFilesQuery(
    { projectName: XDS_PROJECT, repoName: group, revision, filePath: '/**', withContent: true },
    { skip: !isOpen },
  );
  const beforeSkipped = revision <= 1;
  const before = useGetFilesQuery(
    { projectName: XDS_PROJECT, repoName: group, revision: revision - 1, filePath: '/**', withContent: true },
    { skip: !isOpen || beforeSkipped },
  );

  const realError = after.error || (!beforeSkipped && before.error);
  const isLoading = after.isLoading || (!beforeSkipped && before.isLoading);

  const changedFiles = useMemo(() => {
    if (isLoading || realError || !after.data) return [];
    const toMap = (files: FileDto | FileDto[]): Map<string, string> => {
      const arr = Array.isArray(files) ? files : [files];
      return new Map(
        arr.filter((f) => f.type !== 'DIRECTORY').map((f) => [f.path, prettyContent(undefined, f.rawContent)]),
      );
    };
    const afterMap = toMap(after.data);
    const beforeMap = !beforeSkipped && before.data ? toMap(before.data) : new Map<string, string>();
    const result: { path: string; before: string; after: string }[] = [];
    afterMap.forEach((afterContent, path) => {
      const beforeContent = beforeMap.get(path) ?? '';
      if (beforeContent !== afterContent) {
        result.push({ path, before: beforeContent, after: afterContent });
      }
    });
    beforeMap.forEach((beforeContent, path) => {
      if (!afterMap.has(path)) {
        result.push({ path, before: beforeContent, after: '' });
      }
    });
    return result;
  }, [isLoading, realError, after.data, before.data, beforeSkipped]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="6xl" scrollBehavior="inside">
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>
          <HStack spacing={2}>
            <Badge colorScheme="blue">{revision}</Badge>
            <Text fontSize="md" fontWeight="normal" wordBreak="break-all">
              {summary}
            </Text>
          </HStack>
        </ModalHeader>
        <ModalCloseButton />
        <ModalBody pb={6}>
          {isLoading ? (
            <Loading />
          ) : realError ? (
            <Alert status="warning" borderRadius="md" fontSize="sm">
              <AlertIcon />
              Could not load the resource content for this revision.
            </Alert>
          ) : changedFiles.length === 0 ? (
            <Text fontSize="sm" color="gray.500">
              No file changes detected in this commit.
            </Text>
          ) : (
            changedFiles.map(({ path, before: b, after: a }, i) => (
              <Box key={path} mb={6}>
                {i > 0 && <Divider mb={6} />}
                <Heading size="xs" mb={2} color="gray.500" fontFamily="mono">
                  {path}
                </Heading>
                <Text fontSize="xs" color="gray.500" mb={1}>
                  {beforeSkipped
                    ? `Left: empty document (before) · Right: revision ${revision} (after)`
                    : `Left: revision ${revision - 1} (before) · Right: revision ${revision} (after)`}
                </Text>
                <JsonDiffEditor original={b} modified={a} height="40vh" />
              </Box>
            ))
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
};

// Shows the before/after diff of a single resource file at the given revision: the file content at
// `revision - 1` (before the commit) versus at `revision` (after). A missing file on either side (the resource
// was created or deleted in this commit) is shown as an empty document.
const CommitDiffModal = ({
  group,
  filePath,
  revision,
  summary,
  isOpen,
  onClose,
}: {
  group: string;
  filePath: string;
  revision: number;
  summary: string;
  isOpen: boolean;
  onClose: () => void;
}) => {
  const after = useGetFileContentQuery(
    { projectName: XDS_PROJECT, repoName: group, filePath, revision },
    { skip: !isOpen },
  );
  // The first revision has no predecessor, so there is nothing to diff against.
  const beforeSkipped = revision <= 1;
  const before = useGetFileContentQuery(
    { projectName: XDS_PROJECT, repoName: group, filePath, revision: revision - 1 },
    { skip: !isOpen || beforeSkipped },
  );

  const afterError = after.error as FetchBaseQueryError | undefined;
  const beforeError = before.error as FetchBaseQueryError | undefined;
  // A 404 means the file did not exist at that revision (created/deleted in this commit), which is expected.
  const afterMissing = afterError?.status === 404;
  const beforeMissing = beforeSkipped || beforeError?.status === 404;
  const realError = (afterError && !afterMissing) || (beforeError && !beforeMissing);
  const isLoading = after.isLoading || (!beforeSkipped && before.isLoading);

  const afterJson = afterMissing ? '' : prettyContent(after.data?.content, after.data?.rawContent);
  const beforeJson = beforeMissing ? '' : prettyContent(before.data?.content, before.data?.rawContent);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="6xl" scrollBehavior="inside">
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>
          <HStack spacing={2}>
            <Badge colorScheme="blue">{revision}</Badge>
            <Text fontSize="md" fontWeight="normal" wordBreak="break-all">
              {summary}
            </Text>
          </HStack>
        </ModalHeader>
        <ModalCloseButton />
        <ModalBody pb={6}>
          {isLoading ? (
            <Loading />
          ) : realError ? (
            <Alert status="warning" borderRadius="md" fontSize="sm">
              <AlertIcon />
              Could not load the resource content for this revision.
            </Alert>
          ) : (
            <>
              <Text fontSize="xs" color="gray.500" mb={1}>
                {beforeSkipped
                  ? `Left: empty document (before) · Right: revision ${revision} (after)`
                  : `Left: revision ${revision - 1} (before) · Right: revision ${revision} (after)`}
              </Text>
              <JsonDiffEditor original={beforeJson} modified={afterJson} height="70vh" />
            </>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  );
};

// Shows the change history (commit log) of a group. When `filePath` is given, the history is scoped to that
// single resource file (e.g. '/clusters/foo.json') and each commit is clickable to see its before/after diff;
// otherwise it covers the whole group and each commit is still clickable to see all files changed.
export const ResourceHistory = ({ group, filePath }: { group: string; filePath?: string }) => {
  const { data, isLoading, error } = useGetGroupHistoryQuery(
    { group, filePath, maxCommits: MAX_COMMITS },
    { refetchOnMountOrArgChange: true },
  );

  const { isOpen, onOpen, onClose } = useDisclosure();
  const [selected, setSelected] = useState<{ revision: number; summary: string } | null>(null);
  const openDiff = useCallback(
    (revision: number, summary: string) => {
      setSelected({ revision, summary });
      onOpen();
    },
    [onOpen],
  );

  const columns = useMemo(() => {
    const cols: ColumnDef<HistoryDto, unknown>[] = [
      columnHelper.accessor((row) => row.revision, {
        id: 'revision',
        header: 'Revision',
        cell: (info) => (
          <HStack spacing={2}>
            <Icon as={VscGitCommit} color="gray.500" />
            <Badge colorScheme="blue">{info.row.original.revision}</Badge>
          </HStack>
        ),
      }),
      columnHelper.accessor((row) => row.commitMessage.summary, {
        id: 'summary',
        header: 'Summary',
        enableSorting: false,
        cell: (info) => (
          <Button
            variant="link"
            colorScheme="teal"
            fontWeight="normal"
            size="sm"
            whiteSpace="normal"
            textAlign="left"
            wordBreak="break-all"
            onClick={() => openDiff(info.row.original.revision, info.row.original.commitMessage.summary)}
          >
            {info.row.original.commitMessage.summary}
          </Button>
        ),
      }),
      columnHelper.accessor((row) => row.author.name, {
        id: 'author',
        header: 'Author',
        cell: (info) => <Author name={info.getValue()} />,
      }),
      columnHelper.accessor((row) => row.pushedAt, {
        id: 'pushedAt',
        header: 'When',
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
      }),
    ];
    return cols;
  }, [openDiff]);

  // Memoized so the table receives a stable data reference across re-renders (react-table requires this).
  const rows = useMemo(() => data || [], [data]);
  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    initialState: { pagination: { pageSize: 10 } },
  });

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() =>
        rows.length === 0 ? (
          <Text mt={4} color="gray.500">
            No changes have been recorded for this {filePath ? 'resource' : 'group'} yet.
          </Text>
        ) : (
          <>
            <DataTable table={table} />
            {(() => {
              const { pageIndex, pageSize } = table.getState().pagination;
              const pageCount = table.getPageCount();
              return (
                <Flex mt={4} align="center" gap={3} wrap="wrap">
                  <Text fontSize="sm" color="gray.500">
                    {rows.length} {rows.length === 1 ? 'commit' : 'commits'}
                  </Text>
                  <Spacer />
                  <Button
                    size="sm"
                    onClick={() => table.previousPage()}
                    isDisabled={!table.getCanPreviousPage()}
                  >
                    Previous
                  </Button>
                  <Text fontSize="sm">
                    Page {pageCount === 0 ? 0 : pageIndex + 1} of {pageCount}
                  </Text>
                  <Button size="sm" onClick={() => table.nextPage()} isDisabled={!table.getCanNextPage()}>
                    Next
                  </Button>
                  <Select
                    size="sm"
                    w="auto"
                    value={pageSize}
                    onChange={(e) => table.setPageSize(Number(e.target.value))}
                  >
                    {[10, 20, 50, 100].map((size) => (
                      <option key={size} value={size}>
                        {size} / page
                      </option>
                    ))}
                  </Select>
                </Flex>
              );
            })()}
            {selected &&
              (filePath ? (
                <CommitDiffModal
                  group={group}
                  filePath={filePath}
                  revision={selected.revision}
                  summary={selected.summary}
                  isOpen={isOpen}
                  onClose={onClose}
                />
              ) : (
                <GroupCommitDiffModal
                  group={group}
                  revision={selected.revision}
                  summary={selected.summary}
                  isOpen={isOpen}
                  onClose={onClose}
                />
              ))}
          </>
        )
      }
    </Deferred>
  );
};
