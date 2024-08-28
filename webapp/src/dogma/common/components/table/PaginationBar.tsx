import { Flex, IconButton, Input, Select, Spacer, Text } from '@chakra-ui/react';
import { Table as ReactTable } from '@tanstack/react-table';
import { MdNavigateBefore, MdNavigateNext, MdSkipNext, MdSkipPrevious } from 'react-icons/md';

type PaginationBarProps<Data extends object> = {
  table: ReactTable<Data>;
  disableGotoButton?: boolean;
};

export const PaginationBar = <Data extends object>({ table, disableGotoButton }: PaginationBarProps<Data>) => {
  return (
    <Flex gap={2} mt={2} alignItems="center">
      {disableGotoButton && <Spacer />}
      <IconButton
        aria-label="First page"
        icon={<MdSkipPrevious />}
        onClick={() => table.setPageIndex(0)}
        isDisabled={!table.getCanPreviousPage()}
      />
      <IconButton
        aria-label="Prev page"
        icon={<MdNavigateBefore />}
        onClick={() => table.previousPage()}
        isDisabled={!table.getCanPreviousPage()}
      />
      <IconButton
        aria-label="Next page"
        icon={<MdNavigateNext />}
        onClick={() => table.nextPage()}
        isDisabled={!table.getCanNextPage()}
      />
      <IconButton
        aria-label="Last page"
        icon={<MdSkipNext />}
        onClick={() => table.setPageIndex(table.getPageCount() - 1)}
        isDisabled={!table.getCanNextPage()}
      />
      <Text>Page</Text>
      <Text fontWeight="bold">
        {table.getState().pagination.pageIndex + 1} of {table.getPageCount()}
      </Text>
      <Spacer />
      {!disableGotoButton && (
        <>
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
            {[10, 20, 30, 40, 50, 100, 200, 400].map((pageSize) => (
              <option key={pageSize} value={pageSize}>
                Show {pageSize}
              </option>
            ))}
          </Select>
        </>
      )}
    </Flex>
  );
};
