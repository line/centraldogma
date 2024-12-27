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
import { NewTokenRepositoryRole } from 'dogma/features/repo/settings/tokens/NewTokenRepositoryRole';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { UserOrTokenRepositoryRoleList } from 'dogma/features/repo/settings/UserOrTokenRepositoryRoleList';
import {
  useAddTokenRepositoryRoleMutation,
  useDeleteTokenRepositoryRoleMutation,
} from 'dogma/features/api/apiSlice';

const ProjectTokenPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const [addTokenRepositoryRole, { isLoading: isAddTokenLoading }] = useAddTokenRepositoryRoleMutation();
  const [deleteTokenRepositoryRole, { isLoading: isDeleteTokenLoading }] =
    useDeleteTokenRepositoryRoleMutation();
  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'tokens'}>
      {(metadata) => (
        <>
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
          <UserOrTokenRepositoryRoleList
            projectName={projectName}
            repoName={repoName}
            entityType="token"
            userOrTokenRepositoryRole={
              metadata?.repos[repoName]?.roles?.tokens ?? ({} as UserOrTokenRepositoryRoleDto)
            }
            deleteUserOrToken={deleteTokenRepositoryRole}
            isLoading={isDeleteTokenLoading}
          />
        </>
      )}
    </RepositorySettingsView>
  );
};

export default ProjectTokenPage;
