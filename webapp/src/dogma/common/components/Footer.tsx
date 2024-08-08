import { Box, HStack, Spacer, useColorModeValue } from '@chakra-ui/react';
import { FaGithub } from 'react-icons/fa6';
import { useGetTitleQuery } from 'dogma/features/api/apiSlice';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { HiDocumentText } from 'react-icons/hi2';

export const Footer = () => {
  const { data: titleDto } = useGetTitleQuery();
  return (
    <Box padding={6} bg={useColorModeValue('gray.100', 'gray.900')}>
      <HStack width="100%" fontSize="1.05em" fontWeight={'semibold'}>
        <Box>Central Dogma</Box>
        <Box>
          <ChakraLink href="https://github.com/line/centraldogma">
            <FaGithub />
          </ChakraLink>
        </Box>
        <Box>
          <ChakraLink href="https://line.github.io/centraldogma/">
            <HiDocumentText />
          </ChakraLink>
        </Box>
        <Spacer />
        {titleDto?.hostname ? <Box color={'darkgray'}>Hosted at {titleDto.hostname}</Box> : null}
      </HStack>
    </Box>
  );
};
