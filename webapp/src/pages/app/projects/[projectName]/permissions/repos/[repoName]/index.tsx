import { Box, Flex, Heading, Spacer, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import {
  useAddTokenPermissionMutation,
  useAddUserPermissionMutation,
  useDeleteTokenPermissionMutation,
  useDeleteUserPermissionMutation,
  useGetMetadataByProjectNameQuery,
} from 'dogma/features/api/apiSlice';
import { NewRepoUserPermission } from 'dogma/features/repo/permissions/NewRepoUserPermission';
import { RolePermissionForm } from 'dogma/features/repo/permissions/RolePermissionForm';
import { UserPermission } from 'dogma/features/repo/permissions/UserPermission';
import { useRouter } from 'next/router';
import { PerUserPermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { NewRepoTokenPermission } from 'dogma/features/repo/permissions/NewRepoTokenPermission';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Deferred } from 'dogma/common/components/Deferred';

let tabs = ['role', 'user', 'token'];

const RepoMetadataPage = () => {
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
  const [addUserPermission, { isLoading: isAddUserLoading }] = useAddUserPermissionMutation();
  const [deleteUserPermission, { isLoading: isDeleteUserLoading }] = useDeleteUserPermissionMutation();
  const [addTokenPermission, { isLoading: isAddTokenLoading }] = useAddTokenPermissionMutation();
  const [deleteTokenPermission, { isLoading: isDeleteTokenLoading }] = useDeleteTokenPermissionMutation();
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
          <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0, 2]} suffixes={{}} />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">Repository {repoName} - Permissions</Heading>
          </Flex>
          <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
            <TabList>
              {tabs.map((tabName) => (
                <Tab
                  as={Link}
                  key={tabName}
                  replace
                  href={{
                    pathname: `/app/projects/${projectName}/permissions/repos/${repoName}`,
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
              {repoName !== 'meta' && (
                <TabPanel>
                  {metadata?.repos[repoName]?.perRolePermissions && (
                    <RolePermissionForm
                      projectName={projectName}
                      repoName={repoName}
                      perRolePermissions={metadata?.repos[repoName]?.perRolePermissions}
                    />
                  )}
                </TabPanel>
              )}
              <TabPanel>
                <Flex>
                  <Spacer />
                  <NewRepoUserPermission
                    projectName={projectName}
                    repoName={repoName}
                    members={metadata ? Array.from(Object.values(metadata.members)) : []}
                    addUserPermission={addUserPermission}
                    isLoading={isAddUserLoading}
                    perUserPermissions={
                      metadata?.repos[repoName]?.perUserPermissions ?? ({} as PerUserPermissionDto)
                    }
                  />
                </Flex>
                <UserPermission
                  projectName={projectName}
                  repoName={repoName}
                  perUserPermissions={
                    metadata?.repos[repoName]?.perUserPermissions ?? ({} as PerUserPermissionDto)
                  }
                  deleteMember={deleteUserPermission}
                  isLoading={isDeleteUserLoading}
                />
              </TabPanel>
              <TabPanel>
                <Flex>
                  <Spacer />
                  <NewRepoTokenPermission
                    projectName={projectName}
                    repoName={repoName}
                    tokens={metadata ? Array.from(Object.values(metadata.tokens)) : []}
                    addTokenPermission={addTokenPermission}
                    isLoading={isAddTokenLoading}
                    perUserPermissions={
                      metadata?.repos[repoName]?.perTokenPermissions ?? ({} as PerUserPermissionDto)
                    }
                  />
                </Flex>
                <UserPermission
                  projectName={projectName}
                  repoName={repoName}
                  perUserPermissions={
                    metadata?.repos[repoName]?.perTokenPermissions ?? ({} as PerUserPermissionDto)
                  }
                  deleteMember={deleteTokenPermission}
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

export default RepoMetadataPage;
