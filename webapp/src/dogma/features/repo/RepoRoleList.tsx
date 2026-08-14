import { Badge, Icon, Tag, TagLabel, Wrap, WrapItem } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { isPublicRepo, RepositoryMetadataDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { useMemo } from 'react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { GoRepo } from 'react-icons/go';

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
            href={`/app/projects/${projectName}/repos/${info.getValue()}/settings`}
          >
            <Icon as={GoRepo} marginBottom={-0.5} /> {info.getValue()}
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
        cell: (info) =>
          isPublicRepo(info.row.original) ? (
            <Badge fontSize="x-small" colorScheme="teal" variant="outline" borderRadius="full" px={2}>
              Public
            </Badge>
          ) : (
            <Badge fontSize="x-small" colorScheme="gray" variant="outline" borderRadius="full" px={2}>
              Private
            </Badge>
          ),
        header: 'Visibility',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoRoleList;
