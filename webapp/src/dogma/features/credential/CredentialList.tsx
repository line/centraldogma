import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { useGetCredentialsQuery } from 'dogma/features/api/apiSlice';
import { CredentialDto } from 'dogma/features/credential/CredentialDto';
import { Badge, Code } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';

export type CredentialListProps<Data extends object> = {
  projectName: string;
};

const CredentialList = <Data extends object>({ projectName }: CredentialListProps<Data>) => {
  const { data } = useGetCredentialsQuery(projectName);
  const columnHelper = createColumnHelper<CredentialDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: CredentialDto) => row.id, {
        cell: (info) => {
          const id = info.getValue() || 'undefined';
          return (
            <ChakraLink
              href={`/app/projects/${projectName}/credentials/${info.row.original.index}`}
              fontWeight="semibold"
            >
              {id}
            </ChakraLink>
          );
        },
        header: 'ID',
      }),
      columnHelper.accessor((row: CredentialDto) => row.type, {
        cell: (info) => {
          return <Badge colorScheme="green">{info.getValue()}</Badge>;
        },
        header: 'Authentication Type',
      }),
      columnHelper.accessor((row: CredentialDto) => row.hostnamePatterns, {
        cell: (info) => {
          const hostnamePatterns = info.getValue();
          if (hostnamePatterns.length == 0) {
            return '-';
          } else {
            return <Code>{hostnamePatterns.join(', ')}</Code>;
          }
        },
        header: 'Hostnames',
      }),
      columnHelper.accessor((row: CredentialDto) => row.enabled, {
        cell: (info) => {
          if (info.getValue()) {
            return 'Active';
          } else {
            return 'Inactive';
          }
        },
        header: 'Status',
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<CredentialDto>[]} data={data || []} />;
};

export default CredentialList;
