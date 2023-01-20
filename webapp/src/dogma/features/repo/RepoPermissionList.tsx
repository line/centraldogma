import { EditIcon } from '@chakra-ui/icons';
import { HStack, Box, Button, Tag, WrapItem, Wrap, TagLabel } from '@chakra-ui/react';
import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DynamicDataTable } from 'dogma/common/components/table/DynamicDataTable';
import { RepoPermissionDetailDto } from 'dogma/features/repo/RepoPermissionDto';
import { useMemo } from 'react';
import { RiGitRepositoryPrivateFill } from 'react-icons/ri';

export type RepoPermissionListProps<Data extends object> = {
  data: Data[];
  projectName: string;
};

const RepoPermissionList = <Data extends object>({ data, projectName }: RepoPermissionListProps<Data>) => {
  const columnHelper = createColumnHelper<RepoPermissionDetailDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: (info) => (
          <ChakraLink
            fontWeight={'semibold'}
            href={`/app/projects/${projectName}/repos/${info.getValue()}/edit`}
          >
            <HStack>
              <Box>
                <RiGitRepositoryPrivateFill />
              </Box>
              <Box>{info.getValue()}</Box>
            </HStack>
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.owner, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Owner',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.member, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Member',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.perRolePermissions.guest, {
        cell: (info) => (
          <Wrap>
            {info.getValue().map((permission) => (
              <WrapItem key={permission}>
                <Tag borderRadius="full" colorScheme="blue" size="sm">
                  <TagLabel>{permission}</TagLabel>
                </Tag>
              </WrapItem>
            ))}
          </Wrap>
        ),
        header: 'Guest',
        enableSorting: false,
      }),
      columnHelper.accessor((row: RepoPermissionDetailDto) => row.name, {
        cell: () => (
          <Button leftIcon={<EditIcon />} size="sm">
            Edit
          </Button>
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, projectName],
  );
  return <DynamicDataTable columns={columns as ColumnDef<Data>[]} data={data} />;
};

export default RepoPermissionList;
