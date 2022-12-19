import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { NewItemCard } from 'dogma/common/components/NewItemCard';
import { useGetMetadataByProjectNameQuery, useGetReposByProjectNameQuery } from 'dogma/features/api/apiSlice';
import RepoList from 'dogma/features/repo/RepoList';
import RepoPermissionList from 'dogma/features/repo/RepoPermissionList';
import { useRouter } from 'next/router';

const ProjectDetailPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const { data: repoData = [] } = useGetReposByProjectNameQuery(projectName, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  const { data: metadata, isLoading } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  if (isLoading) {
    return <>Loading...</>;
  }
  return (
    <Box p="2">
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Project {projectName}</Heading>
        <Spacer />
        <NewItemCard title="New Repository" label="Name" placeholder="New name here..." />
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
            <RepoList data={repoData} projectName={projectName} />
          </TabPanel>
          <TabPanel>
            <RepoPermissionList data={Array.from(Object.values(metadata.repos))} projectName={projectName} />
          </TabPanel>
          <TabPanel>TODO: Members</TabPanel>
          <TabPanel>TODO: Tokens</TabPanel>
          <TabPanel>TODO: Mirror</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default ProjectDetailPage;
