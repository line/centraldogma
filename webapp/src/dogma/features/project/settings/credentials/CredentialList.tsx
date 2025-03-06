import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { Badge } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { DeleteCredential } from 'dogma/features/project/settings/credentials/DeleteCredential';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type CredentialListProps<Data extends object> = {
  projectName: string;
  repoName?: string;
  credentials: CredentialDto[];
  deleteCredential: (projectName: string, id: string, repoName?: string) => Promise<void>;
  isLoading: boolean;
};

const CredentialList = <Data extends object>({
  projectName,
  repoName,
  credentials,
  deleteCredential,
  isLoading,
}: CredentialListProps<Data>) => {
  const columnHelper = createColumnHelper<CredentialDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: CredentialDto) => row.id, {
        cell: (info) => {
          const id = info.getValue() || 'undefined';
          const credentialLink = repoName
            ? `/app/projects/${projectName}/repos/${repoName}/settings/credentials/${info.row.original.id}`
            : `/app/projects/${projectName}/settings/credentials/${info.row.original.id}`;
          return (
            <ChakraLink href={credentialLink} fontWeight="semibold">
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
      columnHelper.accessor((row: CredentialDto) => row.id, {
        cell: (info) => (
          <DeleteCredential
            projectName={projectName}
            repoName={repoName}
            id={info.getValue()}
            deleteCredential={deleteCredential}
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteCredential, isLoading, projectName, repoName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<CredentialDto>[]} data={credentials || []} />;
};

export default CredentialList;
