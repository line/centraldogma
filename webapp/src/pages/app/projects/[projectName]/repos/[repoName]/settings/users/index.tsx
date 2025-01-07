/*
 * Copyright 2024 LINE Corporation
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
import { NewUserRepositoryRole } from 'dogma/features/repo/settings/users/NewUserRepositoryRole';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { UserOrTokenRepositoryRoleList } from 'dogma/features/repo/settings/UserOrTokenRepositoryRoleList';
import {
  useAddUserRepositoryRoleMutation,
  useDeleteUserRepositoryRoleMutation,
} from 'dogma/features/api/apiSlice';

const RepositoryUserPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const [addUserRepositoryRole, { isLoading: isAddUserLoading }] = useAddUserRepositoryRoleMutation();
  const [deleteUserRepositoryRole, { isLoading: isDeleteUserLoading }] = useDeleteUserRepositoryRoleMutation();
  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'users'}>
      {(metadata) => (
        <>
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
          <UserOrTokenRepositoryRoleList
            projectName={projectName}
            repoName={repoName}
            entityType="user"
            userOrTokenRepositoryRole={
              metadata?.repos[repoName]?.roles?.users ?? ({} as UserOrTokenRepositoryRoleDto)
            }
            deleteUserOrToken={deleteUserRepositoryRole}
            isLoading={isDeleteUserLoading}
          />
        </>
      )}
    </RepositorySettingsView>
  );
};

export default RepositoryUserPage;
