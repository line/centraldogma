import { DeleteIcon, TriangleDownIcon, TriangleUpIcon, ViewIcon } from '@chakra-ui/icons';
import {
  Button,
  ButtonGroup,
  chakra,
  IconButton,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverCloseButton,
  PopoverContent,
  PopoverFooter,
  PopoverHeader,
  PopoverTrigger,
  Table,
  Tbody,
  Td,
  Text,
  Th,
  Thead,
  Tr,
  Wrap,
  WrapItem,
} from '@chakra-ui/react';
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
import Link from 'next/link';
import { useState } from 'react';

export type DynamicDataTableProps<Data extends object> = {
  data: Data[];
  urlPrefix: string;
  columns: ColumnDef<Data, any>[];
};

export const DynamicDataTable = <Data extends object>({
  data,
  urlPrefix,
  columns,
}: DynamicDataTableProps<Data>) => {
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
                        data-testid={`${urlPrefix}-${row.getVisibleCells()[0].getValue()}`}
                        href={`${urlPrefix}${row.getVisibleCells()[0].getValue()}`}
                      >
                        <IconButton colorScheme="blue" aria-label="View" size="sm" icon={<ViewIcon />} />
                      </Link>
                    </WrapItem>
                    <WrapItem>
                      <Popover>
                        <PopoverTrigger>
                          <IconButton colorScheme="red" aria-label="Delete" size="sm" icon={<DeleteIcon />} />
                        </PopoverTrigger>
                        <PopoverContent>
                          <PopoverHeader fontWeight="semibold">Danger</PopoverHeader>
                          <PopoverArrow />
                          <PopoverCloseButton />
                          <PopoverBody>Are you sure you want to continue with your action?</PopoverBody>
                          <PopoverFooter display="flex" justifyContent="flex-end">
                            <ButtonGroup size="sm">
                              <Button variant="outline">Cancel</Button>
                              <Button colorScheme="red">Delete</Button>
                            </ButtonGroup>
                          </PopoverFooter>
                        </PopoverContent>
                      </Popover>
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
