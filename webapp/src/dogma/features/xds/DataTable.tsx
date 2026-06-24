import { TriangleDownIcon, TriangleUpIcon } from '@chakra-ui/icons';
import { chakra, Table, Tbody, Td, Th, Thead, Tr } from '@chakra-ui/react';
import { flexRender, Table as ReactTable } from '@tanstack/react-table';
import { KeyboardEvent } from 'react';

export const DataTable = <Data extends object>({ table }: { table: ReactTable<Data> }) => {
  return (
    <Table mt={4}>
      <Thead>
        {table.getHeaderGroups().map((headerGroup) => (
          <Tr key={headerGroup.id}>
            {headerGroup.headers.map((header) => {
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              const meta: any = header.column.columnDef.meta;
              const canSort = header.column.getCanSort();
              const sortHandler = header.column.getToggleSortingHandler();
              const sorted = header.column.getIsSorted();
              return (
                <Th
                  key={header.id}
                  isNumeric={meta?.isNumeric}
                  aria-sort={sorted === 'desc' ? 'descending' : sorted === 'asc' ? 'ascending' : undefined}
                  // Make sortable headers keyboard-operable (the click handler alone is not focusable).
                  {...(canSort && {
                    tabIndex: 0,
                    role: 'button',
                    cursor: 'pointer',
                    userSelect: 'none',
                    onClick: sortHandler,
                    onKeyDown: (e: KeyboardEvent<HTMLTableCellElement>) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        sortHandler?.(e);
                      }
                    },
                  })}
                >
                  {flexRender(header.column.columnDef.header, header.getContext())}
                  <chakra.span pl="4">
                    {header.column.getIsSorted() ? (
                      header.column.getIsSorted() === 'desc' ? (
                        <TriangleDownIcon aria-label="sorted descending" />
                      ) : (
                        <TriangleUpIcon aria-label="sorted ascending" />
                      )
                    ) : null}
                  </chakra.span>
                </Th>
              );
            })}
          </Tr>
        ))}
      </Thead>
      <Tbody>
        {table.getRowModel().rows.map((row) => {
          return (
            <Tr key={row.id} data-testid="table-row">
              {row.getVisibleCells().map((cell) => {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                const meta: any = cell.column.columnDef.meta;
                return (
                  <Td key={cell.id} isNumeric={meta?.isNumeric}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </Td>
                );
              })}
            </Tr>
          );
        })}
      </Tbody>
    </Table>
  );
};
