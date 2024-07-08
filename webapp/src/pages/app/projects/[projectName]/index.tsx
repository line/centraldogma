import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { MetadataButton } from 'dogma/common/components/MetadataButton';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetReposQuery } from 'dogma/features/api/apiSlice';
import { NewRepo } from 'dogma/features/repo/NewRepo';
import RepoList from 'dogma/features/repo/RepoList';
import { useRouter } from 'next/router';

const ProjectDetailPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const {
    data: repoData,
    isLoading,
    error,
  } = useGetReposQuery(projectName, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0]} />
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
                <Flex gap={2}>
                  <Spacer />
                  {projectName === 'dogma' ? null : (
                    <MetadataButton
                      href={`/app/projects/${projectName}/metadata`}
                      props={{ size: 'sm' }}
                      text={'Project settings'}
                    />
                  )}
                  <NewRepo projectName={projectName} />
                </Flex>
                <RepoList data={repoData || []} projectName={projectName} />
              </TabPanel>
            </TabPanels>
          </Tabs>
        </Box>
      )}
    </Deferred>
  );
};

export default ProjectDetailPage;
