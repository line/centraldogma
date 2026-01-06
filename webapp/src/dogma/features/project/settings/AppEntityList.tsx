import { Text, VStack } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { useMemo } from 'react';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { DeleteAppEntity } from 'dogma/features/project/settings/DeleteAppEntity';

export type AppEntityListProps<Data extends object> = {
  data: Data[];
  projectName: string;
  entityType: 'member' | 'appIdentity';
  getId: (row: Data) => string;
  getRole: (row: Data) => string;
  getAddedBy: (row: Data) => string;
  getTimestamp: (row: Data) => string;
  showDeleteButton?: (row: Data) => boolean;
  deleteMutation: (projectName: string, id: string) => Promise<void>;
  isLoading: boolean;
};

const AppEntityList = <Data extends object>({
  data,
  projectName,
  entityType,
  getId,
  getRole,
  getAddedBy,
  getTimestamp,
  showDeleteButton = () => true,
  deleteMutation,
  isLoading,
}: AppEntityListProps<Data>): JSX.Element => {
  const columnHelper = createColumnHelper<Data>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: Data) => getId(row), {
        cell: (info) => (
          <VStack alignItems="left">
            <Text>{info.getValue()}</Text>
            <Text>
              <UserRole role={getRole(info.row.original)} />
            </Text>
          </VStack>
        ),
        header: entityType === 'member' ? 'Login ID' : 'App ID',
      }),
      columnHelper.accessor((row: Data) => getAddedBy(row), {
        cell: (info) => info.getValue(),
        header: 'Added By',
      }),
      columnHelper.accessor((row: Data) => getTimestamp(row), {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Added At',
      }),
      columnHelper.accessor((row: Data) => getId(row), {
        cell: (info) =>
          showDeleteButton(info.row.original) ? (
            <DeleteAppEntity
              projectName={projectName}
              id={info.getValue()}
              entityType={entityType}
              deleteEntity={deleteMutation}
              isLoading={isLoading}
            />
          ) : null,
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [
      columnHelper,
      projectName,
      entityType,
      getId,
      getRole,
      getAddedBy,
      getTimestamp,
      showDeleteButton,
      deleteMutation,
      isLoading,
    ],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default AppEntityList;
