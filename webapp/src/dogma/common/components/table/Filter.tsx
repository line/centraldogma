import { Column } from '@tanstack/react-table';
import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { useMemo } from 'react';

export type FilterProps<Data> = {
  column: Column<Data, unknown>;
};

export const Filter = <Data extends object>({ column }: FilterProps<Data>) => {
  const columnFilterValue = column.getFilterValue();
  const sortedUniqueValues = useMemo(() => Array.from(column.getFacetedUniqueValues().keys()).sort(), [column]);

  return (
    <>
      <datalist id={column.id + 'list'}>
        {sortedUniqueValues.slice(0, 5000).map((value: string | number) => (
          <option value={value} key={value} />
        ))}
      </datalist>
      <DebouncedInput
        type="text"
        value={(columnFilterValue ?? '') as string}
        onChange={(value) => column.setFilterValue(value)}
        placeholder={`Search...`}
        className="w-36 border shadow rounded"
        list={column.id + 'list'}
      />
      <div className="h-1" />
    </>
  );
};
