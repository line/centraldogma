import { Flex, IconButton, Text } from '@chakra-ui/react';
import {
  ColumnDef,
  ColumnFiltersState,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  SortingState,
  useReactTable,
} from '@tanstack/react-table';
import { DataTable } from 'dogma/common/components/table/DataTable';
import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { PaginationBar } from 'dogma/common/components/table/PaginationBar';
import { useMemo, useState } from 'react';
import { RxReset } from 'react-icons/rx';

export type DynamicDataTableProps<Data extends object> = {
  data: Data[];
  columns: ColumnDef<Data>[];
  clientPagination?: boolean;
};

export const DataTableClientPagination = <Data extends object>({
  data,
  columns,
  clientPagination = false,
}: DynamicDataTableProps<Data>) => {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const table = useReactTable({
    columns: columns || [],
    data: data || [],
    getCoreRowModel: getCoreRowModel(),
    onSortingChange: setSorting,
    getSortedRowModel: getSortedRowModel(),
    onColumnFiltersChange: setColumnFilters,
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    state: {
      sorting,
      columnFilters,
    },
  });
  const column = table.getHeaderGroups()[0].headers[0].column; // Filter by the 1st column
  const columnFilterValue = column.getFilterValue();
  const sortedUniqueValues = useMemo(() => Array.from(column.getFacetedUniqueValues().keys()).sort(), [column]);

  return (
    <>
      <Text mb={4}>Filter by {table.getHeaderGroups()[0].headers[0].id} </Text>
      <datalist id={column.id + 'list'}>
        {sortedUniqueValues.slice(0, 5000).map((value: string | number) => (
          <option value={value} key={value} />
        ))}
      </datalist>
      <Flex gap={2}>
        <DebouncedInput
          type="text"
          value={(columnFilterValue ?? '') as string}
          onChange={(value) => value && column.setFilterValue(value)}
          placeholder={`Search...`}
          list={column.id + 'list'}
        />
        <IconButton
          aria-label="reset"
          icon={<RxReset />}
          colorScheme="teal"
          onClick={() => column.setFilterValue('')}
        />
      </Flex>
      <DataTable table={table} aria-label={''} />
      {clientPagination && <PaginationBar table={table} />}
    </>
  );
};
