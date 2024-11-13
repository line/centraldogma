import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useGetMirrorsQuery, useDeleteMirrorMutation } from 'dogma/features/api/apiSlice';
import { Badge, Button, Code, HStack, Link, Wrap, WrapItem } from '@chakra-ui/react';
import { GoRepo } from 'react-icons/go';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import { MirrorDto } from 'dogma/features/project/settings/mirrors/MirrorDto';
import { RunMirror } from '../../../mirror/RunMirrorButton';
import { FaPlay } from 'react-icons/fa';
import { DeleteMirror } from 'dogma/features/project/settings/mirrors/DeleteMirror';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type MirrorListProps<Data extends object> = {
  projectName: string;
};

const MirrorList = <Data extends object>({ projectName }: MirrorListProps<Data>) => {
  const { data } = useGetMirrorsQuery(projectName);
  const [deleteMirror, { isLoading }] = useDeleteMirrorMutation();
  const columnHelper = createColumnHelper<MirrorDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: MirrorDto) => `${row.id}`, {
        cell: (info) => {
          const id = info.getValue();
          return (
            <Link
              href={`/app/projects/${projectName}/settings/mirrors/${info.row.original.id}`}
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
      columnHelper.accessor((row: MirrorDto) => row.id, {
        cell: (info) => (
          <HStack>
            <Wrap>
              <WrapItem>
                <RunMirror mirror={info.row.original}>
                  {({ isLoading, onToggle }) => (
                    <Button
                      isDisabled={!info.row.original.enabled}
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
              id={info.getValue()}
              deleteMirror={(projectName, id) => deleteMirror({ projectName, id }).unwrap()}
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
