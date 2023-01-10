import { DeleteIcon } from '@chakra-ui/icons';
import { Button } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { UserRole } from 'dogma/common/components/UserRole';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoTokenDetailDto } from 'dogma/features/repo/RepoTokenDto';

export type RepoTokenListProps<Data extends object> = {
  data: Data[];
};

const RepoTokenList = <Data extends object>({ data }: RepoTokenListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoTokenDetailDto>();
  const columns = [
    columnHelper.accessor((row: RepoTokenDetailDto) => row.appId, {
      cell: (info) => info.getValue(),
      header: 'App ID',
    }),
    columnHelper.accessor((row: RepoTokenDetailDto) => row.role, {
      cell: (info) => <UserRole role={info.getValue()} />,
      header: 'Role',
    }),
    columnHelper.accessor((row: RepoTokenDetailDto) => row.creation.user, {
      cell: (info) => info.getValue(),
      header: 'Added By',
    }),
    columnHelper.accessor((row: RepoTokenDetailDto) => row.creation.timestamp, {
      cell: (info) => <DateWithTooltip date={info.getValue()} />,
      header: 'Added At',
    }),
    columnHelper.accessor((row: RepoTokenDetailDto) => row.appId, {
      cell: () => (
        <Button leftIcon={<DeleteIcon />} size="sm" colorScheme="red">
          Delete
        </Button>
      ),
      header: 'Actions',
      enableSorting: false,
    }),
  ];
  return <DynamicDataTable columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoTokenList;
