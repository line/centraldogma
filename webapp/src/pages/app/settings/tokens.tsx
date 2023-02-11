import { Badge, Box, Flex, Heading, Spacer, Text, Wrap } from '@chakra-ui/react';
import { createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { NewToken } from 'dogma/features/token/NewToken';
import { SecretWrapper } from 'dogma/features/token/SecretWrapper';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useGetTokensQuery } from 'dogma/features/api/apiSlice';
import { TokenDto } from 'dogma/features/token/TokenDto';
import { useMemo } from 'react';
import { DeactivateToken } from 'dogma/features/token/DeactivateToken';
import { ActivateToken } from 'dogma/features/token/ActivateToken';
import { DeleteToken } from 'dogma/features/token/DeleteToken';

const TokenPage = () => {
  const columnHelper = createColumnHelper<TokenDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: TokenDto) => row.appId, {
        cell: (info) =>
          info.row.original.secret ? (
            <SecretWrapper appId={info.getValue()} secret={info.row.original.secret} />
          ) : (
            <Text>{info.getValue()}</Text>
          ),
        header: 'Application ID',
      }),
      columnHelper.accessor((row: TokenDto) => row.admin, {
        cell: (info) => <UserRole role={info.getValue() ? 'Admin' : 'User'} />,
        header: 'Level',
      }),
      columnHelper.accessor((row: TokenDto) => row.creation.user, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Created By',
      }),
      columnHelper.accessor((row: TokenDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created At',
      }),
      columnHelper.accessor((row: TokenDto) => row.deactivation, {
        cell: (info) => (
          <Badge colorScheme={info.getValue() ? 'gray' : 'blue'}>
            {info.getValue() ? 'Inactive' : 'Active'}
          </Badge>
        ),
        header: 'Status',
      }),
      columnHelper.accessor((row: TokenDto) => row.deactivation, {
        cell: (info) => (
          <Wrap>
            <ActivateToken appId={info.row.original.appId} hidden={info.getValue() === undefined} />
            <DeactivateToken appId={info.row.original.appId} hidden={info.getValue() !== undefined} />
            <DeleteToken appId={info.row.original.appId} hidden={info.getValue() === undefined} />
          </Wrap>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper],
  );
  const { data = [], error, isLoading } = useGetTokensQuery();
  if (isLoading) {
    return <>Loading...</>;
  }
  if (error) {
    return <>{JSON.stringify(error)}</>;
  }
  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Application Tokens</Heading>
      </Flex>
      <Flex>
        <Spacer />
        <NewToken />
      </Flex>
      <DataTableClientPagination columns={columns} data={data} />
    </Box>
  );
};

export default TokenPage;
