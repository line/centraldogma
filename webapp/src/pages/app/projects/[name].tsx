import { useGetReposByProjectNameQuery } from 'dogma/features/api/apiSlice';
import RepositoryList from 'dogma/features/repository/RepositoryList';
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
import { useRouter } from 'next/router';

const ProjectDetailPage = () => {
  const router = useRouter();
  const { name } = router.query;
  const { data = [] } = useGetReposByProjectNameQuery(name as string, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Project {name}</Heading>
        <Spacer />
        <ButtonGroup gap="2">
          <Tag size="lg" variant="subtle" colorScheme="blue">
            <AddIcon mr={2} />
            <TagLabel>New Repository</TagLabel>
          </Tag>
        </ButtonGroup>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg">
        <TabList>
          <Tab>
            <Heading size="sm">Repositories</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">Permissions</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">Members</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">Tokens</Heading>
          </Tab>
          <Tab>
            <Heading size="sm">Mirror</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel>
            <RepositoryList data={data} name={name as string} />
          </TabPanel>
          <TabPanel>TODO: Permissions</TabPanel>
          <TabPanel>TODO: Members</TabPanel>
          <TabPanel>TODO: Tokens</TabPanel>
          <TabPanel>TODO: Mirror</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default ProjectDetailPage;
