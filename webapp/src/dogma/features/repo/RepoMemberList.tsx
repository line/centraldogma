import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepoMemberDetailDto } from 'dogma/features/repo/RepoMemberDto';
import { useMemo } from 'react';

export type RepoMemberListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoMemberList = <Data extends object>({ data, projectName }: RepoMemberListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoMemberDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoMemberDetailDto) => row.login, {
        cell: (info) => info.getValue(),
        header: 'Login ID',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.role, {
        cell: (info) => <UserRole role={info.getValue()} />,
        header: 'Role',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.creation.user, {
        cell: (info) => info.getValue(),
        header: 'Added By',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Added At',
      }),
    ],
    [columnHelper],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoMemberList;
