import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useGetReposQuery } from 'dogma/features/api/apiSlice';
import { NewRepo } from 'dogma/features/repo/NewRepo';
import RepoList from 'dogma/features/repo/RepoList';
import { useRouter } from 'next/router';

const ProjectDetailPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const { data: repoData, isLoading } = useGetReposQuery(projectName, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  if (isLoading) {
    return <>Loading...</>;
  }
  return (
    <Box p="2">
      <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0]} suffixes={{ 4: '/list/head' }} />
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Project {projectName}</Heading>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg">
        <TabList>
          <Tab>
            <Heading size="sm">Repositories</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          <TabPanel>
            <Flex>
              <Spacer />
              <NewRepo projectName={projectName} />
            </Flex>
            <RepoList data={repoData || []} projectName={projectName} />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default ProjectDetailPage;
