import { Text } from '@chakra-ui/react';
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
import { Filter } from 'dogma/common/components/table/Filter';
import { PaginationBar } from 'dogma/common/components/table/PaginationBar';
import { useState } from 'react';

export type DataTableClientPaginationProps<Data extends object> = {
  data: Data[];
  columns: ColumnDef<Data>[];
};

export const DataTableClientPagination = <Data extends object>({
  data,
  columns,
}: DataTableClientPaginationProps<Data>) => {
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
    manualPagination: false,
    autoResetPageIndex: false,
    state: {
      sorting,
      columnFilters,
    },
  });

  return (
    <>
      <Text mb="8px">Filter by {table.getHeaderGroups()[0].headers[0].id} </Text>
      <Filter
        table={table}
        column={table.getHeaderGroups()[0].headers[0].column /* Filter by the 1st column */}
      />
      <DataTable table={table} aria-label={''} />
      <PaginationBar table={table} />
    </>
  );
};
