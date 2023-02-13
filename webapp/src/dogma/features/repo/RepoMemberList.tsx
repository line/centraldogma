import { Text, VStack } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { DeleteMember } from 'dogma/features/repo/DeleteMember';
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
        cell: (info) => (
          <VStack alignItems="left">
            <Text>{info.getValue()}</Text>
            <Text>
              <UserRole role={info.row.original.role} />
            </Text>
          </VStack>
        ),
        header: 'Login ID',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.creation.user, {
        cell: (info) => info.getValue(),
        header: 'Added By',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Added At',
      }),
      columnHelper.accessor((row: RepoMemberDetailDto) => row.login, {
        cell: (info) => <DeleteMember projectName={projectName} id={info.getValue()} />,
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoMemberList;
