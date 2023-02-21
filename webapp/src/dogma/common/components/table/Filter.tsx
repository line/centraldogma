import { Column } from '@tanstack/react-table';
import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { useCallback, useMemo } from 'react';

export type FilterProps<Data> = {
  column: Column<Data, unknown>;
};

export const Filter = <Data extends object>({ column }: FilterProps<Data>) => {
  const columnFilterValue = column.getFilterValue();
  const facetedUniqueValues = column.getFacetedUniqueValues();
  const sortedUniqueValues = useMemo(
    () => Array.from(facetedUniqueValues.keys()).sort(),
    [facetedUniqueValues],
  );
  const handleChange = useCallback((value: string | number) => column.setFilterValue(value), [column]);

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
        onChange={handleChange}
        placeholder={`Search...`}
        list={column.id + 'list'}
      />
    </>
  );
};
