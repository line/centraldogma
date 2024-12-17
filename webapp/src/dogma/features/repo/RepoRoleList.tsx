import { Icon, Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepositoryMetadataDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { useMemo } from 'react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { RiGitRepositoryPrivateLine } from 'react-icons/ri';

export type RepoRoleListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoRoleList = <Data extends object>({ data, projectName }: RepoRoleListProps<Data>) => {
  const columnHelper = createColumnHelper<RepositoryMetadataDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepositoryMetadataDto) => row.name, {
        cell: (info) => (
          <ChakraLink
            fontWeight={'semibold'}
            href={`/app/projects/${projectName}/repos/${info.getValue()}/roles`}
          >
            <Icon as={RiGitRepositoryPrivateLine} marginBottom={-0.5} /> {info.getValue()}
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepositoryMetadataDto) => row.roles.projects.member, {
        cell: (info) => (
          <Wrap>
            {info.getValue() !== null && (
              <WrapItem key={info.getValue()}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{info.getValue()}</TagLabel>
                </Tag>
              </WrapItem>
            )}
          </Wrap>
        ),
        header: 'Member',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepositoryMetadataDto) => row.roles.projects.guest, {
        cell: (info) => (
          <Wrap>
            {info.getValue() !== null && (
              <WrapItem key={info.getValue()}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{info.getValue()}</TagLabel>
                </Tag>
              </WrapItem>
            )}
          </Wrap>
        ),
        header: 'Guest',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoRoleList;
