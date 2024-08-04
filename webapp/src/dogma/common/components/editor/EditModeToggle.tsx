import { Text, FormLabel, Switch } from '@chakra-ui/react';
import { ChangeEventHandler } from 'react';

export const EditModeToggle = ({
  switchMode,
  value,
  label,
}: {
  switchMode: ChangeEventHandler;
  value: boolean;
  label: string;
}) => {
  return (
    <>
      <FormLabel htmlFor="edit" mb="2">
        <Text>{label}</Text>
      </FormLabel>
      <Switch id="edit" colorScheme="teal" onChange={switchMode} isChecked={!value} />
    </>
  );
};
