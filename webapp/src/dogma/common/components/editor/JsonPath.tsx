import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { Box, Text } from '@chakra-ui/react';
import jp from 'jsonpath';

export const JsonPath = ({
  setFileContent,
  jsonContent,
}: {
  setFileContent: (value: string) => void;
  jsonContent: string;
}) => {
  const handleOnChange = (value: string) => {
    try {
      if (value) {
        setFileContent(JSON.stringify(jp.query(jsonContent, value), null, 2));
      } else if (value === '') {
        setFileContent(JSON.stringify(jsonContent, null, 2));
      }
    } catch (err) {
      setFileContent('Invalid JSONPath.');
    }
  };
  return (
    <Box mb="4">
      <Text mb="2">Query by JSONPath</Text>
      <DebouncedInput type="text" value="" onChange={handleOnChange} placeholder={`$..[0]`} />
    </Box>
  );
};
