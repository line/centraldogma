import { Badge, Box, Flex, Spacer, Text, Wrap } from '@chakra-ui/react';
import { createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { CertificateWrapper } from 'dogma/features/app-identity/CertificateWrapper';
import { NewAppIdentity } from 'dogma/features/app-identity/NewAppIdentity';
import { SecretWrapper } from 'dogma/features/app-identity/SecretWrapper';
import { UserRole } from 'dogma/common/components/UserRole';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useGetAppIdentitiesQuery } from 'dogma/features/api/apiSlice';
import { AppIdentityDto, isToken, isCertificate } from 'dogma/features/app-identity/AppIdentity';
import { useMemo } from 'react';
import { DeactivateAppIdentity } from 'dogma/features/app-identity/DeactivateAppIdentity';
import { ActivateAppIdentity } from 'dogma/features/app-identity/ActivateAppIdentity';
import { DeleteAppIdentity } from 'dogma/features/app-identity/DeleteAppIdentity';
import { Deferred } from 'dogma/common/components/Deferred';
import SettingView from 'dogma/features/settings/SettingView';

const AppIdentityPage = () => {
  const columnHelper = createColumnHelper<AppIdentityDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: AppIdentityDto) => row.type, {
        cell: (info) => (
          <Badge colorScheme={info.getValue() === 'TOKEN' ? 'purple' : 'green'}>
            {info.getValue() === 'TOKEN' ? 'Token' : 'Certificate'}
          </Badge>
        ),
        header: 'Type',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.appId, {
        cell: (info) => {
          const identity = info.row.original;

          if (isToken(identity)) {
            return identity.secret ? (
              <SecretWrapper appId={info.getValue()} secret={identity.secret} />
            ) : (
              <Text>{info.getValue()}</Text>
            );
          }

          if (isCertificate(identity)) {
            return <CertificateWrapper appId={info.getValue()} certificateId={identity.certificateId} />;
          }

          return <Text>{info.getValue()}</Text>;
        },
        header: 'Application ID',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.systemAdmin, {
        cell: (info) => <UserRole role={info.getValue() ? 'System Admin' : 'User'} />,
        header: 'Level',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.creation.user, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Created By',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Created At',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.deactivation, {
        cell: (info) => (
          <Badge colorScheme={info.getValue() ? 'gray' : 'blue'}>
            {info.getValue() ? 'Inactive' : 'Active'}
          </Badge>
        ),
        header: 'Status',
      }),
      columnHelper.accessor((row: AppIdentityDto) => row.deactivation, {
        cell: (info) => (
          <Wrap>
            <ActivateAppIdentity appId={info.row.original.appId} hidden={info.getValue() === undefined} />
            <DeactivateAppIdentity appId={info.row.original.appId} hidden={info.getValue() !== undefined} />
            <DeleteAppIdentity appId={info.row.original.appId} hidden={info.getValue() === undefined} />
          </Wrap>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper],
  );
  const { data, error, isLoading } = useGetAppIdentitiesQuery();
  return (
    <SettingView currentTab={'Application Identities'}>
      <Deferred isLoading={isLoading} error={error}>
        {() => (
          <Box p="2">
            <Flex>
              <Spacer />
              <NewAppIdentity />
            </Flex>
            <DataTableClientPagination columns={columns} data={data || []} />
          </Box>
        )}
      </Deferred>
    </SettingView>
  );
};

export default AppIdentityPage;
