import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { NewItemCard } from 'dogma/common/components/NewItemCard';
import { useGetMetadataByProjectNameQuery, useGetReposByProjectNameQuery } from 'dogma/features/api/apiSlice';
import RepoList from 'dogma/features/repo/RepoList';
import RepoMemberList from 'dogma/features/repo/RepoMemberList';
import RepoPermissionList from 'dogma/features/repo/RepoPermissionList';
import Link from 'next/link';
import { useRouter } from 'next/router';
import { useEffect, useState } from 'react';

const tabs = ['repositories', 'permissions', 'members', 'tokens', 'mirror'];

const ProjectDetailPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName as string;
  const { data: repoData = [] } = useGetReposByProjectNameQuery(projectName, {
    refetchOnMountOrArgChange: true,
    skip: false,
  });
  const { data: metadata, isLoading } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
    skip: false,
  });
  const [tabIndex, setTabIndex] = useState(0);
  const switchTab = (index: number) => {
    setTabIndex(index);
    window.location.hash = tabs[index];
  };
  useEffect(() => {
    const index = tabs.findIndex((tab) => tab === window.location.hash?.slice(1));
    if (index !== -1) {
      setTabIndex(index);
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
      <Tabs variant="enclosed-colored" size="lg" index={tabIndex} onChange={switchTab}>
        <TabList>
          {tabs.map((tab) => (
            <Tab as={Link} key={tab} href={`#${tab}`} shallow={true}>
              <Heading size="sm">
                {tab.charAt(0).toUpperCase()}
                {tab.slice(1)}
              </Heading>
            </Tab>
          ))}
        </TabList>
        <TabPanels>
          <TabPanel>
            <RepoList data={repoData} projectName={projectName} />
          </TabPanel>
          <TabPanel>
            <RepoPermissionList data={Array.from(Object.values(metadata.repos))} projectName={projectName} />
          </TabPanel>
          <TabPanel>
            <RepoMemberList data={Array.from(Object.values(metadata.members))} />
          </TabPanel>
          <TabPanel>TODO: Tokens</TabPanel>
          <TabPanel>TODO: Mirror</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default ProjectDetailPage;
