import { Input } from '@chakra-ui/react';
import { useState, useEffect } from 'react';

type DebouncedInputProps = {
  value: string | number;
  onChange: (value: string | number) => void;
  debounce?: number;
  clearTrigger?: boolean;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'onChange'>;
export const DebouncedInput = ({
  value: initialValue,
  onChange,
  debounce = 500,
  clearTrigger = false,
  ...props
}: DebouncedInputProps) => {
  const [value, setValue] = useState(initialValue);
  const [oldValue, setOldValue] = useState(initialValue);

  useEffect(() => {
    const timeout = setTimeout(() => {
      if (value === oldValue) {
        return;
      }
      setOldValue(value);
      onChange(value);
    }, debounce);

    return () => clearTimeout(timeout);
  }, [debounce, onChange, value, setOldValue, oldValue]);

  useEffect(() => {
    if (clearTrigger) {
      setValue('');
      setOldValue('');
      onChange('');
    }
  }, [clearTrigger, onChange]);

  return (
    <Input
      {...props}
      value={value}
      onChange={(e) => setValue(e.target.value)}
      size="md"
      focusBorderColor="gray.500"
    />
  );
};
