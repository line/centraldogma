import { Box, Flex, Heading, HStack, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import {
  useAddTokenRepositoryRoleMutation,
  useAddUserRepositoryRoleMutation,
  useDeleteTokenRepositoryRoleMutation,
  useDeleteUserRepositoryRoleMutation,
  useGetMetadataByProjectNameQuery,
} from 'dogma/features/api/apiSlice';
import { NewUserRepositoryRole } from 'dogma/features/repo/roles/NewUserRepositoryRole';
import { ProjectRolesForm } from 'dogma/features/repo/roles/ProjectRolesForm';
import { UserRepositoryRole } from 'dogma/features/repo/roles/UserRepositoryRole';
import { useRouter } from 'next/router';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { NewTokenRepositoryRole } from 'dogma/features/repo/roles/NewTokenRepositoryRole';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Deferred } from 'dogma/common/components/Deferred';
import { GoRepo } from 'react-icons/go';
import { isInternalRepo } from 'dogma/util/repo-util';

let tabs = ['role', 'user', 'token'];

const RepoRolePage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  if (repoName === 'meta') {
    tabs = ['user', 'token'];
  }
  const {
    data: metadata,
    isLoading,
    error,
  } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
    skip: false,
  });
  const [addUserRepositoryRole, { isLoading: isAddUserLoading }] = useAddUserRepositoryRoleMutation();
  const [deleteUserRepositoryRole, { isLoading: isDeleteUserLoading }] = useDeleteUserRepositoryRoleMutation();
  const [addTokenRepositoryRole, { isLoading: isAddTokenLoading }] = useAddTokenRepositoryRoleMutation();
  const [deleteTokenRepositoryRole, { isLoading: isDeleteTokenLoading }] =
    useDeleteTokenRepositoryRoleMutation();
  const [tabIndex, setTabIndex] = useState(0);
  const tab = router.query.tab ? (router.query.tab as string) : '';
  useEffect(() => {
    const index = tabs.findIndex((tabName) => tabName === tab);
    if (index !== -1 && index !== tabIndex) {
      setTabIndex(index);
    }
  }, [tab, tabIndex]);
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0, 3]} suffixes={{}} />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">
              <HStack>
                <Box color={'teal'}>
                  <GoRepo />
                </Box>
                <Box color={'teal'}>{repoName}</Box>
                <Box>roles</Box>
              </HStack>
            </Heading>
          </Flex>
          <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
            <TabList>
              {tabs.map((tabName) => (
                <Tab
                  as={Link}
                  key={tabName}
                  replace
                  href={{
                    pathname: `/app/projects/${projectName}/repos/${repoName}/roles`,
                    query: { tab: tabName },
                  }}
                >
                  <Heading size="sm">
                    {tabName.charAt(0).toUpperCase()}
                    {tabName.slice(1)}
                  </Heading>
                </Tab>
              ))}
            </TabList>
            <TabPanels>
              {!isInternalRepo(repoName) && (
                <TabPanel>
                  {metadata?.repos[repoName]?.roles?.projects && (
                    <ProjectRolesForm
                      projectName={projectName}
                      repoName={repoName}
                      projectRoles={metadata.repos[repoName].roles.projects}
                    />
                  )}
                </TabPanel>
              )}
              <TabPanel>
                <Flex>
                  <Spacer />
                  <NewUserRepositoryRole
                    projectName={projectName}
                    repoName={repoName}
                    members={metadata ? Array.from(Object.values(metadata.members)) : []}
                    addUserRepositoryRole={addUserRepositoryRole}
                    isLoading={isAddUserLoading}
                    userRepositoryRole={
                      metadata?.repos[repoName]?.roles?.users ?? ({} as UserOrTokenRepositoryRoleDto)
                    }
                  />
                </Flex>
                <UserRepositoryRole
                  projectName={projectName}
                  repoName={repoName}
                  userOrTokenRepositoryRole={
                    metadata?.repos[repoName]?.roles?.users ?? ({} as UserOrTokenRepositoryRoleDto)
                  }
                  deleteMember={deleteUserRepositoryRole}
                  isLoading={isDeleteUserLoading}
                />
              </TabPanel>
              <TabPanel>
                <Flex>
                  <Spacer />
                  <NewTokenRepositoryRole
                    projectName={projectName}
                    repoName={repoName}
                    tokens={metadata ? Array.from(Object.values(metadata.tokens)) : []}
                    addTokenRepositoryRole={addTokenRepositoryRole}
                    isLoading={isAddTokenLoading}
                    tokenRepositoryRole={
                      metadata?.repos[repoName]?.roles?.tokens ?? ({} as UserOrTokenRepositoryRoleDto)
                    }
                  />
                </Flex>
                <UserRepositoryRole
                  projectName={projectName}
                  repoName={repoName}
                  userOrTokenRepositoryRole={
                    metadata?.repos[repoName]?.roles?.tokens ?? ({} as UserOrTokenRepositoryRoleDto)
                  }
                  deleteMember={deleteTokenRepositoryRole}
                  isLoading={isDeleteTokenLoading}
                />
              </TabPanel>
            </TabPanels>
          </Tabs>
        </Box>
      )}
    </Deferred>
  );
};

export default RepoRolePage;
