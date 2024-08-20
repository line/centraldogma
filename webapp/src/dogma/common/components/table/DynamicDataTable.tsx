import { Text } from '@chakra-ui/react';
import {
  ColumnDef,
  ColumnFiltersState,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  PaginationState,
  SortingState,
  useReactTable,
} from '@tanstack/react-table';
import { DataTable } from 'dogma/common/components/table/DataTable';
import { Filter } from 'dogma/common/components/table/Filter';
import { PaginationBar } from 'dogma/common/components/table/PaginationBar';
import { Dispatch, SetStateAction, useState } from 'react';

export type DynamicDataTableProps<Data extends object> = {
  data: Data[];
  columns: ColumnDef<Data>[];
  pagination?: { pageIndex: number; pageSize: number };
  setPagination?: Dispatch<SetStateAction<PaginationState>>;
  pageCount?: number;
};

export const DynamicDataTable = <Data extends object>({
  data,
  columns,
  pagination,
  setPagination,
  pageCount,
}: DynamicDataTableProps<Data>) => {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const table = useReactTable({
    columns: columns || [],
    data: data || [],
    pageCount: pageCount,
    getCoreRowModel: getCoreRowModel(),
    onSortingChange: setSorting,
    getSortedRowModel: getSortedRowModel(),
    onColumnFiltersChange: setColumnFilters,
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    onPaginationChange: setPagination,
    manualPagination: true,
    state: {
      sorting,
      columnFilters,
      pagination,
    },
  });

  return (
    <>
      <Text mb="8px">Filter by {table.getHeaderGroups()[0].headers[0].id} </Text>
      <Filter
        table={table}
        column={table.getHeaderGroups()[0].headers[0].column /* Filter by the 1st column */}
      />
      <DataTable table={table} />
      {pagination && <PaginationBar table={table} />}
    </>
  );
};
