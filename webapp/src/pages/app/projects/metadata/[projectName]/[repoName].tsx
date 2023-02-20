import { Box, Flex, Heading, Tabs, TabList, Tab, TabPanels, TabPanel, Spacer } from '@chakra-ui/react';
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
import { PerUserPermissionDto, RepoRolePermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import { NewRepoTokenPermission } from 'dogma/features/repo/permissions/NewRepoTokenPermission';

const RepoMetadata = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const { data: metadata, isLoading } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
    skip: false,
  });
  const [addUserPermission, { isLoading: isAddUserLoading }] = useAddUserPermissionMutation();
  const [deleteUserPermission, { isLoading: isDeleteUserLoading }] = useDeleteUserPermissionMutation();
  const [addTokenPermission, { isLoading: isAddTokenLoading }] = useAddTokenPermissionMutation();
  const [deleteTokenPermission, { isLoading: isDeleteTokenLoading }] = useDeleteTokenPermissionMutation();
  if (isLoading) {
    return <>Loading...</>;
  }
  return (
    <Box p="2">
      <Breadcrumbs path={router.asPath.split('?')[0]} omitIndexList={[0, 2]} suffixes={{}} />
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">Repository {repoName} - Permissions</Heading>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg">
        <TabList>
          {repoName !== 'meta' && (
            <Tab key="role">
              <Heading size="sm">Role</Heading>
            </Tab>
          )}
          <Tab key="user">
            <Heading size="sm">User</Heading>
          </Tab>
          <Tab key="token">
            <Heading size="sm">Token</Heading>
          </Tab>
        </TabList>
        <TabPanels>
          {repoName !== 'meta' && (
            <TabPanel>
              <RolePermissionForm
                projectName={projectName}
                repoName={repoName}
                perRolePermissions={
                  metadata ? metadata.repos[repoName].perRolePermissions : ({} as RepoRolePermissionDto)
                }
              />
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
                  metadata ? metadata.repos[repoName].perUserPermissions : ({} as PerUserPermissionDto)
                }
              />
            </Flex>
            <UserPermission
              projectName={projectName}
              repoName={repoName}
              perUserPermissions={
                metadata ? metadata.repos[repoName].perUserPermissions : ({} as PerUserPermissionDto)
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
                  metadata ? metadata.repos[repoName].perTokenPermissions : ({} as PerUserPermissionDto)
                }
              />
            </Flex>
            <UserPermission
              projectName={projectName}
              repoName={repoName}
              perUserPermissions={
                metadata ? metadata.repos[repoName].perTokenPermissions : ({} as PerUserPermissionDto)
              }
              deleteMember={deleteTokenPermission}
              isLoading={isDeleteTokenLoading}
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default RepoMetadata;
