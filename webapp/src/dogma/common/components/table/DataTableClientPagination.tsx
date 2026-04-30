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
import { PAGE_SIZES, PaginationBar } from 'dogma/common/components/table/PaginationBar';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/router';

const VALID_PAGE_SIZES: Set<number> = new Set(PAGE_SIZES);

function parsePageIndex(value: string | string[] | undefined): number {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n - 1 : 0;
}

function parsePageSize(value: string | string[] | undefined): number {
  const n = Number(value);
  return VALID_PAGE_SIZES.has(n) ? n : 10;
}

export type DataTableClientPaginationProps<Data extends object> = {
  data: Data[];
  columns: ColumnDef<Data>[];
};

export const DataTableClientPagination = <Data extends object>({
  data,
  columns,
}: DataTableClientPaginationProps<Data>) => {
  const router = useRouter();

  const [sorting, setSorting] = useState<SortingState>([]);
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);
  const [pagination, setPaginationState] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });

  const setPagination = useCallback(
    (updater: PaginationState | ((old: PaginationState) => PaginationState)) => {
      const newState = typeof updater === 'function' ? updater(pagination) : updater;
      setPaginationState(newState);
      const query = { ...router.query };
      if (newState.pageIndex === 0) {
        delete query.page;
      } else {
        query.page = String(newState.pageIndex + 1);
      }
      if (newState.pageSize === 10) {
        delete query.pageSize;
      } else {
        query.pageSize = String(newState.pageSize);
      }
      router.push({ pathname: router.pathname, query }, undefined, { shallow: true });
    },
    [pagination, router],
  );

  useEffect(() => {
    if (!router.isReady) {
      return;
    }
    setPaginationState({
      pageIndex: parsePageIndex(router.query?.page),
      pageSize: parsePageSize(router.query?.pageSize),
    });
  }, [router.isReady, router.query?.page, router.query?.pageSize]);

  const table = useReactTable({
    columns: columns || [],
    data: data || [],
    getCoreRowModel: getCoreRowModel(),
    onSortingChange: setSorting,
    getSortedRowModel: getSortedRowModel(),
    onColumnFiltersChange: setColumnFilters,
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    onPaginationChange: setPagination,
    manualPagination: false,
    autoResetPageIndex: false,
    state: {
      sorting,
      columnFilters,
      pagination,
    },
  });

  if (!router.isReady) {
    return null;
  }

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
