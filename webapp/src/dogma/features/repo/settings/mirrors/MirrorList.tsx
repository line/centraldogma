import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import {
  useDeleteMirrorMutation,
  useGetProjectMirrorsQuery,
  useGetMirrorsQuery,
} from 'dogma/features/api/apiSlice';
import { Badge, Button, Code, HStack, Link, Tooltip, Wrap, WrapItem } from '@chakra-ui/react';
import { GoRepo } from 'react-icons/go';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import { MirrorDto } from 'dogma/features/repo/settings/mirrors/MirrorRequest';
import { RunMirror } from 'dogma/features/mirror/RunMirrorButton';
import { FaPlay } from 'react-icons/fa';
import { DeleteMirror } from 'dogma/features/repo/settings/mirrors/DeleteMirror';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type MirrorListProps<Data extends object> = {
  projectName: string;
  repoName?: string;
};

const useGetMirrors = (projectName: string, repoName?: string): { data: MirrorDto[]; isLoading: boolean } => {
  const { data: projectMirrors = [], isLoading: isProjectLoading } = useGetProjectMirrorsQuery(projectName, {
    skip: !!repoName, // Skip if repoName is provided
  });

  const { data: repoMirrors = [], isLoading: isRepoLoading } = useGetMirrorsQuery(
    { projectName, repoName: repoName! },
    { skip: !repoName }, // Skip if repoName is not provided
  );

  return {
    data: repoName ? repoMirrors : projectMirrors,
    isLoading: repoName ? isRepoLoading : isProjectLoading,
  };
};

const MirrorList = <Data extends object>({ projectName, repoName }: MirrorListProps<Data>) => {
  const { data } = useGetMirrors(projectName, repoName);
  const [deleteMirror, { isLoading }] = useDeleteMirrorMutation();
  const columnHelper = createColumnHelper<MirrorDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: MirrorDto) => `${row.id}`, {
        cell: (info) => {
          const id = info.getValue();
          return (
            <Link
              href={`/app/projects/${projectName}/repos/${info.row.original.localRepo}/settings/mirrors/${id}`}
              fontWeight="semibold"
            >
              {id ?? 'unknown'}
            </Link>
          );
        },
        header: 'ID',
      }),
      columnHelper.accessor((row: MirrorDto) => `${row.localRepo}`, {
        cell: (info) => {
          info.column;
          const repo = info.getValue();
          return (
            <>
              <Link href={`/app/projects/${projectName}/repos/${repo}/tree/head${info.row.original.localPath}`}>
                <LabelledIcon icon={GoRepo} text={repo} />
              </Link>
            </>
          );
        },
        header: 'Repo',
      }),
      columnHelper.accessor((row: MirrorDto) => row.remoteUrl, {
        cell: (info) => info.getValue(),
        header: 'Remote',
      }),
      columnHelper.accessor((row: MirrorDto) => row.schedule, {
        cell: (info) => {
          return (
            <Code variant="outline" p={1}>
              {info.getValue() || 'disabled'}
            </Code>
          );
        },
        header: 'Schedule',
      }),
      columnHelper.accessor((row: MirrorDto) => row.enabled, {
        cell: (info) => {
          if (info.getValue()) {
            return <Badge colorScheme={'green'}>Enabled</Badge>;
          } else {
            return <Badge colorScheme={'red'}>Disabled</Badge>;
          }
        },
        header: 'Status',
      }),
      columnHelper.accessor((row: MirrorDto) => row.allow, {
        cell: (info) => {
          if (info.getValue()) {
            return <Badge colorScheme={'blue'}>Allowed</Badge>;
          } else {
            return (
              <Tooltip label="Access to the remote repository is disallowed. Please contact the administrator.">
                <Badge colorScheme={'red'}>Disallowed</Badge>
              </Tooltip>
            );
          }
        },
        header: 'Access',
      }),
      columnHelper.accessor((row: MirrorDto) => row.id, {
        cell: (info) => (
          <HStack>
            <Wrap>
              <WrapItem>
                <RunMirror mirror={info.row.original}>
                  {({ isLoading, onToggle }) => (
                    <Button
                      isDisabled={!info.row.original.enabled || !info.row.original.allow}
                      onClick={onToggle}
                      colorScheme={'green'}
                      size="sm"
                      aria-label="Run mirror"
                      isLoading={isLoading}
                      leftIcon={<FaPlay />}
                    >
                      Run
                    </Button>
                  )}
                </RunMirror>
              </WrapItem>
            </Wrap>
            <DeleteMirror
              projectName={projectName}
              repoName={info.row.original.localRepo}
              id={info.getValue()}
              deleteMirror={(projectName, repoName, id) => deleteMirror({ projectName, repoName, id }).unwrap()}
              isLoading={isLoading}
            />
          </HStack>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteMirror, isLoading, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<MirrorDto>[]} data={data || []} />;
};

export default MirrorList;
