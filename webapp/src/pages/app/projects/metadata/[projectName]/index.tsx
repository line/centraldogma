import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { NewRepo } from 'dogma/features/repo/NewRepo';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import AppMemberList from 'dogma/features/metadata/AppMemberList';
import RepoPermissionList from 'dogma/features/repo/RepoPermissionList';
import AppTokenList from 'dogma/features/metadata/AppTokenList';
import Link from 'next/link';
import { useRouter } from 'next/router';
import { useEffect, useState } from 'react';
import RepoMetaList from 'dogma/features/metadata/RepoMetaList';
import { DeleteProject } from 'dogma/features/project/DeleteProject';
import { NewMember } from 'dogma/features/metadata/NewMember';
import { NewAppToken } from 'dogma/features/metadata/NewAppToken';
import { useAppSelector } from 'dogma/store';

let tabs = ['repositories', 'permissions', 'members', 'tokens', 'mirror'];

const ProjectMetadataPage = () => {
  const { user } = useAppSelector((state) => state.auth);
  if (!user) {
    tabs = ['repositories', 'mirror'];
  }
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
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
      <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0, 2]} suffixes={{ 4: '/list/head' }} />
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Project {projectName} - Metadata</Heading>
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
            <Flex gap={3}>
              <Spacer />
              <DeleteProject projectName={projectName} />
              <NewRepo projectName={projectName} />
            </Flex>
            <RepoMetaList
              data={metadata ? Array.from(Object.values(metadata.repos)) : []}
              projectName={projectName}
            />
          </TabPanel>
          {user && (
            <TabPanel>
              <RepoPermissionList
                data={metadata ? Array.from(Object.values(metadata.repos).filter((repo) => !repo.removal)) : []}
                projectName={projectName}
                members={metadata ? Array.from(Object.values(metadata.members)) : []}
              />
            </TabPanel>
          )}
          {user && (
            <TabPanel>
              <Flex>
                <Spacer />
                <NewMember projectName={projectName} />
              </Flex>
              <AppMemberList
                data={metadata ? Array.from(Object.values(metadata.members)) : []}
                projectName={projectName}
              />
            </TabPanel>
          )}
          {user && (
            <TabPanel>
              <Flex>
                <Spacer />
                <NewAppToken projectName={projectName} />
              </Flex>
              <AppTokenList
                data={metadata ? Array.from(Object.values(metadata.tokens)) : []}
                projectName={projectName}
              />
            </TabPanel>
          )}
          <TabPanel>Coming soon</TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default ProjectMetadataPage;