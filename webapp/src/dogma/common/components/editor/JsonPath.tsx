import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { Box, Text } from '@chakra-ui/react';

export const JsonPath = ({ handleQuery }: { handleQuery: (value: string | number) => void }) => {
  return (
    <Box mb="4">
      <Text mb="2">Query by JSON Path</Text>
      <DebouncedInput type="text" value="" onChange={handleQuery} placeholder={`$..[0]`} />
    </Box>
  );
};
