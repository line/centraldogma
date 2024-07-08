import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { MirrorDto } from 'dogma/features/mirror/MirrorDto';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useGetMirrorsQuery } from 'dogma/features/api/apiSlice';
import { Badge, Code, Link } from '@chakra-ui/react';
import { GoRepo } from 'react-icons/go';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type MirrorListProps<Data extends object> = {
  projectName: string;
};

const MirrorList = <Data extends object>({ projectName }: MirrorListProps<Data>) => {
  const { data } = useGetMirrorsQuery(projectName);
  const columnHelper = createColumnHelper<MirrorDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: MirrorDto) => `${row.id}`, {
        cell: (info) => {
          const id = info.getValue();
          return (
            <Link href={`/app/projects/${projectName}/mirrors/${info.row.original.id}`} fontWeight="semibold">
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
              <Link href={`/app/projects/${projectName}/repos/${repo}/list/head${info.row.original.localPath}`}>
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
      columnHelper.accessor((row: MirrorDto) => row.direction, {
        cell: (info) => <Badge colorScheme={'blue'}>{info.getValue()}</Badge>,
        header: 'Direction',
      }),
      columnHelper.accessor((row: MirrorDto) => row.schedule, {
        cell: (info) => (
          <Code variant="outline" p={1}>
            {info.getValue()}
          </Code>
        ),
        header: 'Schedule',
      }),
      columnHelper.accessor((row: MirrorDto) => row.enabled, {
        cell: (info) => {
          if (info.getValue()) {
            return <Badge colorScheme="green">Active</Badge>;
          } else {
            return <Badge>Inactive</Badge>;
          }
        },
        header: 'Status',
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<MirrorDto>[]} data={data || []} />;
};

export default MirrorList;
