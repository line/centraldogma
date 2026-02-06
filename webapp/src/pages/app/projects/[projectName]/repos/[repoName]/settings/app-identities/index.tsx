/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import { useRouter } from 'next/router';
import { Flex, Spacer } from '@chakra-ui/react';
import RepositorySettingsView from 'dogma/features/repo/settings/RepositorySettingsView';
import { NewAppIdentityRepositoryRole } from 'dogma/features/repo/settings/app-identities/NewAppIdentityRepositoryRole';
import { UserOrAppIdentityRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { UserOrAppIdentityRepositoryRoleList } from 'dogma/features/repo/settings/UserOrAppIdentityRepositoryRoleList';
import {
  useAddAppIdentityRepositoryRoleMutation,
  useDeleteAppIdentityRepositoryRoleMutation,
} from 'dogma/features/api/apiSlice';

const RepositoryAppIdentityPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const [addAppIdentityRepositoryRole, { isLoading: isAddAppIdentityLoading }] =
    useAddAppIdentityRepositoryRoleMutation();
  const [deleteAppIdentityRepositoryRole, { isLoading: isDeleteAppIdentityLoading }] =
    useDeleteAppIdentityRepositoryRoleMutation();
  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'App Identities'}>
      {(metadata) => (
        <>
          <Flex>
            <Spacer />
            <NewAppIdentityRepositoryRole
              projectName={projectName}
              repoName={repoName}
              appIds={metadata ? Array.from(Object.values(metadata.appIds)) : []}
              addAppIdentityRepositoryRole={addAppIdentityRepositoryRole}
              isLoading={isAddAppIdentityLoading}
              appIdentityRepositoryRole={
                metadata?.repos[repoName]?.roles?.appIds ?? ({} as UserOrAppIdentityRepositoryRoleDto)
              }
            />
          </Flex>
          <UserOrAppIdentityRepositoryRoleList
            projectName={projectName}
            repoName={repoName}
            entityType="appIdentity"
            userOrAppIdentityRepositoryRole={
              metadata?.repos[repoName]?.roles?.appIds ?? ({} as UserOrAppIdentityRepositoryRoleDto)
            }
            deleteUserOrAppIdentity={deleteAppIdentityRepositoryRole}
            isLoading={isDeleteAppIdentityLoading}
          />
        </>
      )}
    </RepositorySettingsView>
  );
};

export default RepositoryAppIdentityPage;
