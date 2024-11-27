import { Column, Table } from '@tanstack/react-table';
import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { useCallback, useEffect, useMemo } from 'react';

export type FilterProps<Data> = {
  table: Table<Data>;
  column: Column<Data, unknown>;
  clearFilter?: boolean;
  setClearFilter?: (value: boolean) => void;
};

export const Filter = <Data extends object>({
  table,
  column,
  clearFilter = false,
  setClearFilter,
}: FilterProps<Data>) => {
  const columnFilterValue = column.getFilterValue();
  const facetedUniqueValues = column.getFacetedUniqueValues();
  const sortedUniqueValues = useMemo(
    () => Array.from(facetedUniqueValues.keys()).sort(),
    [facetedUniqueValues],
  );
  const handleChange = useCallback(
    (value: string | number) => {
      table.setPageIndex(0);
      column.setFilterValue(value);
    },
    [table, column],
  );

  useEffect(() => {
    if (clearFilter) {
      handleChange('');
      setClearFilter?.(false);
    }
  }, [clearFilter, handleChange, setClearFilter]);

  return (
    <>
      <datalist id={column.id + 'list'}>
        {sortedUniqueValues.slice(0, 5000).map((value: string | number) => (
          <option value={value} key={value} />
        ))}
      </datalist>
      <DebouncedInput
        clearTrigger={clearFilter}
        type="text"
        value={(columnFilterValue ?? '') as string}
        onChange={handleChange}
        placeholder={`Search...`}
        list={column.id + 'list'}
      />
    </>
  );
};
