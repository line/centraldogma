/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import {
  Button,
  Flex,
  HStack,
  Input,
  InputGroup,
  InputLeftElement,
  Link,
  Select,
  Spacer,
  Text,
} from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { AiOutlineSearch } from 'react-icons/ai';
import {
  createColumnHelper,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { useMemo, useState } from 'react';
import { DataTable } from 'dogma/features/xds/DataTable';
import { GroupDto } from 'dogma/features/xds/XdsTypes';

const columnHelper = createColumnHelper<GroupDto>();

const PAGE_SIZES = [10, 20, 50, 100];

export const GroupList = ({ groups }: { groups: GroupDto[] }) => {
  const [globalFilter, setGlobalFilter] = useState('');

  // The group can be deleted from within the group (group detail page), not from this list, so that deletion
  // requires opening the group first.
  const columns = useMemo(
    () => [
      columnHelper.accessor('id', {
        cell: (info) => (
          <Link
            as={RouteLink}
            href={`/app/xds/group?name=${encodeURIComponent(info.getValue())}&type=overview`}
            color="teal"
          >
            {info.getValue()}
          </Link>
        ),
        header: 'Group',
      }),
    ],
    [],
  );

  const table = useReactTable({
    data: groups,
    columns,
    state: { globalFilter },
    onGlobalFilterChange: setGlobalFilter,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    initialState: { pagination: { pageSize: 10 } },
  });

  if (groups.length === 0) {
    return <Text color="gray.500">No groups yet. Create one to get started.</Text>;
  }

  const { pageIndex, pageSize } = table.getState().pagination;
  const filteredCount = table.getFilteredRowModel().rows.length;
  const pageCount = table.getPageCount();

  return (
    <>
      <HStack mb={2}>
        <InputGroup maxW="320px">
          <InputLeftElement pointerEvents="none">
            <AiOutlineSearch color="gray" />
          </InputLeftElement>
          <Input
            placeholder="Search groups"
            value={globalFilter}
            onChange={(e) => setGlobalFilter(e.target.value)}
          />
        </InputGroup>
      </HStack>

      {filteredCount === 0 ? (
        <Text mt={4} color="gray.500">
          No groups match &quot;{globalFilter}&quot;.
        </Text>
      ) : (
        <DataTable table={table} />
      )}

      <Flex mt={4} align="center" gap={3} wrap="wrap">
        <Text fontSize="sm" color="gray.500">
          {filteredCount} {filteredCount === 1 ? 'group' : 'groups'}
        </Text>
        <Spacer />
        <Button size="sm" onClick={() => table.previousPage()} isDisabled={!table.getCanPreviousPage()}>
          Previous
        </Button>
        <Text fontSize="sm">
          Page {pageCount === 0 ? 0 : pageIndex + 1} of {pageCount}
        </Text>
        <Button size="sm" onClick={() => table.nextPage()} isDisabled={!table.getCanNextPage()}>
          Next
        </Button>
        <Select size="sm" w="auto" value={pageSize} onChange={(e) => table.setPageSize(Number(e.target.value))}>
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>
              {size} / page
            </option>
          ))}
        </Select>
      </Flex>
    </>
  );
};
