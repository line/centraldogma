import { Spinner, Text, VStack } from '@chakra-ui/react';

export const Loading = () => (
  <VStack mt="25%">
    <Spinner thickness="4px" speed="0.65s" emptyColor="gray.200" color="teal" size="xl" />
    <Text>Loading...</Text>
  </VStack>
);
