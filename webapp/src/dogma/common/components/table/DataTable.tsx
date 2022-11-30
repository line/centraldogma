import { DeleteIcon, TriangleDownIcon, TriangleUpIcon, ViewIcon } from '@chakra-ui/icons';
import { chakra, IconButton, Table, Tbody, Td, Text, Th, Thead, Tr, Wrap, WrapItem } from '@chakra-ui/react';
import {
  ColumnDef,
  ColumnFiltersState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  SortingState,
  useReactTable,
} from '@tanstack/react-table';
import { Filter } from 'dogma/common/components/table/Filter';
import { RepoDataTableDto } from 'dogma/features/repository/RepoDto';
import Link from 'next/link';
import { useState } from 'react';

export type DataTableProps<Data extends object> = {
  data: Data[];
  name: string;
  columns: ColumnDef<Data, any>[];
};

export const DataTable = <Data extends object>({ data, name, columns }: DataTableProps<Data>) => {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const table = useReactTable({
    columns,
    data,
    getCoreRowModel: getCoreRowModel(),
    onSortingChange: setSorting,
    getSortedRowModel: getSortedRowModel(),
    onColumnFiltersChange: setColumnFilters,
    getFilteredRowModel: getFilteredRowModel(),
    state: {
      sorting,
      columnFilters,
    },
  });

  return (
    <>
      <Text mb="8px">Filter by {table.getHeaderGroups()[0].headers[0].id /* Filter by the 1st column */} </Text>
      <Filter column={table.getHeaderGroups()[0].headers[0].column /* Filter by the 1st column */} />
      <Table variant="striped">
        <Thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <Tr key={headerGroup.id}>
              {headerGroup.headers.map((header) => {
                // see https://tanstack.com/table/v8/docs/api/core/column-def#meta to type this correctly
                const meta: any = header.column.columnDef.meta;
                return (
                  <Th
                    key={header.id}
                    onClick={header.column.getToggleSortingHandler()}
                    isNumeric={meta?.isNumeric}
                  >
                    {flexRender(header.column.columnDef.header, header.getContext())}

                    <chakra.span pl="4">
                      {header.column.getIsSorted() ? (
                        header.column.getIsSorted() === 'desc' ? (
                          <TriangleDownIcon aria-label="sorted descending" />
                        ) : (
                          <TriangleUpIcon aria-label="sorted ascending" />
                        )
                      ) : (
                        <TriangleUpIcon aria-label="sorted ascending" />
                      )}
                    </chakra.span>
                  </Th>
                );
              })}
              <Th>Actions</Th>
            </Tr>
          ))}
        </Thead>
        <Tbody data-testid="table-body">
          {table.getRowModel().rows.map((row) => {
            return (
              <Tr key={row.id} data-testid="table-row">
                {row.getVisibleCells().map((cell) => {
                  // see https://tanstack.com/table/v8/docs/api/core/column-def#meta to type this correctly
                  const meta: any = cell.column.columnDef.meta;
                  return (
                    <Td key={cell.id} isNumeric={meta?.isNumeric}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </Td>
                  );
                })}
                <Td>
                  <Wrap>
                    <WrapItem>
                      <Link
                        data-testid={`view-repo-${(row.original as RepoDataTableDto).name}`}
                        href={`${name}/repos/${(row.original as RepoDataTableDto).name}`}
                      >
                        <IconButton colorScheme="blue" aria-label="View" size="md" icon={<ViewIcon />} />
                      </Link>
                    </WrapItem>
                    <WrapItem>
                      <IconButton colorScheme="blue" aria-label="Delete" size="md" icon={<DeleteIcon />} />
                    </WrapItem>
                  </Wrap>
                </Td>
              </Tr>
            );
          })}
        </Tbody>
      </Table>
    </>
  );
};
