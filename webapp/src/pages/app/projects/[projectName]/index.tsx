import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { NewItemCard } from 'dogma/common/components/NewItemCard';
import { useGetMetadataByProjectNameQuery, useGetReposByProjectNameQuery } from 'dogma/features/api/apiSlice';
import RepoList from 'dogma/features/repo/RepoList';
import RepoPermissionList from 'dogma/features/repo/RepoPermissionList';
import Link from 'next/link';
import { useRouter } from 'next/router';
import { useEffect, useState } from 'react';

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
  const [tabIndex, setTabIndex] = useState(0);
  useEffect(() => {
    switch (window.location.hash) {
      case '#permissions':
        setTabIndex(1);
        break;
      case '#members':
        setTabIndex(2);
        break;
      case '#tokens':
        setTabIndex(3);
        break;
      case '#mirror':
        setTabIndex(4);
        break;
      default:
        break;
    }
  }, []);
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
      <Tabs variant="enclosed-colored" size="lg" index={tabIndex} onChange={(index) => setTabIndex(index)}>
        <TabList>
          <Tab as={Link} href="#repositories">
            <Heading size="sm">Repositories</Heading>
          </Tab>
          <Tab as={Link} href="#permissions">
            <Heading size="sm">Permissions</Heading>
          </Tab>
          <Tab as={Link} href="#members">
            <Heading size="sm">Members</Heading>
          </Tab>
          <Tab as={Link} href="#tokens">
            <Heading size="sm">Tokens</Heading>
          </Tab>
          <Tab as={Link} href="#mirror">
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
