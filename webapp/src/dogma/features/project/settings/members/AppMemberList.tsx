import { Text, VStack } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useDeleteMemberMutation } from 'dogma/features/api/apiSlice';
import { useMemo } from 'react';
import { AppMemberDetailDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { DeleteMember } from 'dogma/features/project/settings/members/DeleteMember';
import { useAppSelector } from 'dogma/hooks';

export type AppMemberListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const AppMemberList = <Data extends object>({ data, projectName }: AppMemberListProps<Data>) => {
  const [deleteMember, { isLoading }] = useDeleteMemberMutation();
  const columnHelper = createColumnHelper<AppMemberDetailDto>();
  const auth = useAppSelector((state) => state.auth);
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: AppMemberDetailDto) => row.login, {
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
      columnHelper.accessor((row: AppMemberDetailDto) => row.creation.user, {
        cell: (info) => info.getValue(),
        header: 'Added By',
      }),
      columnHelper.accessor((row: AppMemberDetailDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Added At',
      }),
      columnHelper.accessor((row: AppMemberDetailDto) => row.login, {
        cell: (info) =>
          // Only show delete button if the user is not the current user.
          info.getValue() !== auth.user?.email ? (
            <DeleteMember
              projectName={projectName}
              id={info.getValue()}
              deleteMember={(projectName, id) => deleteMember({ projectName, id }).unwrap()}
              isLoading={isLoading}
            />
          ) : null,
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteMember, isLoading, projectName, auth],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default AppMemberList;
