import { DeleteIcon } from '@chakra-ui/icons';
import { Button, Badge } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoMemberDetailDto } from 'dogma/features/repo/RepoMemberDto';

export type RepoMemberListProps<Data extends object> = {
  data: Data[];
};

const RepoMemberList = <Data extends object>({ data }: RepoMemberListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoMemberDetailDto>();
  const columns = [
    columnHelper.accessor((row: RepoMemberDetailDto) => row.login, {
      cell: (info) => info.getValue(),
      header: 'Login ID',
    }),
    columnHelper.accessor((row: RepoMemberDetailDto) => row.role, {
      cell: (info) => (
        <Badge colorScheme={info.getValue().toLowerCase() === 'owner' ? 'blue' : 'green'}>
          {info.getValue()}
        </Badge>
      ),
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
    columnHelper.accessor((row: RepoMemberDetailDto) => row.login, {
      cell: () => (
        <Button leftIcon={<DeleteIcon />} size="sm" colorScheme="red">
          Delete
        </Button>
      ),
      header: 'Actions',
      enableSorting: false,
    }),
  ];
  return <DynamicDataTable columns={columns as ColumnDef<Data, any>[]} data={data} />;
};

export default RepoMemberList;
