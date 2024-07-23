import { Flex, Input, Text, Select, Spacer, IconButton } from '@chakra-ui/react';
import { Table as ReactTable } from '@tanstack/react-table';
import { MdNavigateBefore, MdNavigateNext, MdSkipNext, MdSkipPrevious } from 'react-icons/md';

export const PaginationBar = <Data extends object>({ table }: { table: ReactTable<Data> }) => {
  return (
    <Flex gap={2} mt={2} alignItems="center">
      <IconButton
        aria-label="First page"
        icon={<MdSkipPrevious />}
        onClick={() => table.setPageIndex(0)}
        disabled={!table.getCanPreviousPage()}
      />
      <IconButton
        aria-label="Prev page"
        icon={<MdNavigateBefore />}
        onClick={() => table.previousPage()}
        disabled={!table.getCanPreviousPage()}
      />
      <IconButton
        aria-label="Next page"
        icon={<MdNavigateNext />}
        onClick={() => table.nextPage()}
        disabled={!table.getCanNextPage()}
      />
      <IconButton
        aria-label="Last page"
        icon={<MdSkipNext />}
        onClick={() => table.setPageIndex(table.getPageCount() - 1)}
        disabled={!table.getCanNextPage()}
      />
      <Text>Page</Text>
      <Text fontWeight="bold">
        {table.getState().pagination.pageIndex + 1} of {table.getPageCount()}
      </Text>
      <Spacer />
      <Text>Go to page:</Text>
      <Input
        type="number"
        defaultValue={table.getState().pagination.pageIndex + 1}
        onChange={(e) => {
          const page = e.target.value ? Number(e.target.value) - 1 : 0;
          table.setPageIndex(page);
        }}
        width={20}
      />
      <Select
        value={table.getState().pagination.pageSize}
        onChange={(e) => {
          table.setPageSize(Number(e.target.value));
        }}
        width="auto"
      >
        {[10, 20, 30, 40, 50].map((pageSize) => (
          <option key={pageSize} value={pageSize}>
            Show {pageSize}
          </option>
        ))}
      </Select>
    </Flex>
  );
};
