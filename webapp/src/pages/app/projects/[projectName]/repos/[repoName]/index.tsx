import { AddIcon } from '@chakra-ui/icons';
import { Box, ButtonGroup, Flex, Heading, Spacer, Tag, TagLabel } from '@chakra-ui/react';
import { useRouter } from 'next/router';

const RepositoryDetailPage = () => {
  const router = useRouter();
  const { repoName } = router.query;
  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Repository {repoName}</Heading>
        <Spacer />
        <ButtonGroup gap="2">
          <Tag size="lg" variant="subtle" colorScheme="blue">
            <AddIcon mr={2} />
            <TagLabel>New File</TagLabel>
          </Tag>
        </ButtonGroup>
      </Flex>
    </Box>
  );
};

export default RepositoryDetailPage;
