import { Box, Flex, Heading, HStack, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetReposQuery } from 'dogma/features/api/apiSlice';
import { NewRepo } from 'dogma/features/repo/NewRepo';
import RepoList from 'dogma/features/repo/RepoList';
import { useRouter } from 'next/router';
import { FiBox } from 'react-icons/fi';
import { ProjectSettingsButton } from 'dogma/common/components/ProjectSettingsButton';
import { RepoStatusTag } from 'dogma/features/repo/RepoStatusTag';
import { useProjectReadOnly } from 'dogma/features/repo/useReadOnly';

const ProjectDetailPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const projectReadOnly = useProjectReadOnly(projectName);
  const {
    data: repoData,
    isLoading,
    error,
  } = useGetReposQuery(projectName, {
    refetchOnMountOrArgChange: true,
  });
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0]} />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">
              <HStack color="teal">
                <Box>
                  <FiBox />
                </Box>
                <Box>{projectName}</Box>
              </HStack>
            </Heading>
            {projectReadOnly && <RepoStatusTag status="READ_ONLY" />}
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
                  <ProjectSettingsButton projectName={projectName} />
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
