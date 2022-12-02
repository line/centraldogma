import { AddIcon } from '@chakra-ui/icons';
import {
  Box,
  ButtonGroup,
  Flex,
  Heading,
  Spacer,
  Tab,
  TabList,
  TabPanel,
  TabPanels,
  Tabs,
  Tag,
  TagLabel,
} from '@chakra-ui/react';
import { useGetFilesByProjectAndRepoNameQuery } from 'dogma/features/api/apiSlice';
import FileList from 'dogma/features/file/FileList';
import { useRouter } from 'next/router';

const RepositoryDetailPage = () => {
  const router = useRouter();
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const { data = [] } = useGetFilesByProjectAndRepoNameQuery(
    { projectName, repoName },
    {
      refetchOnMountOrArgChange: true,
      skip: false,
    },
  );
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
      <Tabs variant="enclosed-colored" size="lg">
        <TabList>
          <Tab>
            <Heading size="sm">Files</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">History</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel>
            <FileList data={data} projectName={projectName as string} repoName={repoName as string} />
          </TabPanel>
          <TabPanel>TODO: History</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default RepositoryDetailPage;
