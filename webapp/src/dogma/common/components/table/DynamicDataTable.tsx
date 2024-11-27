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
import React, { ReactElement, useState } from 'react';

export type DynamicDataTableProps<Data extends object> = {
  data: Data[];
  columns: ColumnDef<Data>[];
  pagination?: { pageIndex: number; pageSize: number };
  setPagination?: (updater: (old: PaginationState) => PaginationState) => void;
  pageCount?: number;
  disableGotoButton?: boolean;
  onEmptyData?: ReactElement;
};

export const DynamicDataTable = <Data extends object>({
  data,
  columns,
  pagination,
  setPagination,
  pageCount,
  disableGotoButton,
  onEmptyData,
}: DynamicDataTableProps<Data>) => {
  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [clearFilter, setClearFilter] = useState(false);
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

  const resetTable = () => {
    setColumnFilters([]);
    setPagination?.(() => ({ pageIndex: 0, pageSize: 10 }));
    setClearFilter(true);
  };

  return (
    <>
      {table.getRowModel().rows.length == 0 && onEmptyData ? (
        onEmptyData
      ) : (
        <>
          <Text mb="8px">Filter by {table.getHeaderGroups()[0].headers[0].id} </Text>
          <Filter
            table={table}
            column={table.getHeaderGroups()[0].headers[0].column /* Filter by the 1st column */}
            clearFilter={clearFilter}
            setClearFilter={setClearFilter}
          />
          <DataTable
            table={table}
            onRowClick={() => {
              resetTable();
            }}
          />
        </>
      )}
      {pagination && <PaginationBar table={table} disableGotoButton={disableGotoButton} />}
    </>
  );
};
