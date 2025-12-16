import { DebouncedInput } from 'dogma/common/components/table/DebouncedInput';
import { Box, Text } from '@chakra-ui/react';
import jp from 'jsonpath';
import { parseContent, stringifyContent } from 'dogma/features/file/StructuredFileSupport';

export const JsonPath = ({
  language,
  setFileContent,
  originalContent,
}: {
  language: string;
  setFileContent: (value: string) => void;
  originalContent: string;
}) => {
  const handleOnChange = (value: string) => {
    try {
      if (value) {
        const jsonContent = parseContent(language, originalContent);
        setFileContent(stringifyContent(language, jp.query(jsonContent, value)));
      } else if (value === '') {
        setFileContent(originalContent);
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
