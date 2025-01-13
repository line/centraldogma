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
import { Button, Flex, Spacer } from '@chakra-ui/react';
import Link from 'next/link';
import { AiOutlinePlus } from 'react-icons/ai';
import React from 'react';
import { useGetRepoCredentialsQuery, useDeleteRepoCredentialMutation } from 'dogma/features/api/apiSlice';
import RepositorySettingsView from 'dogma/features/repo/settings/RepositorySettingsView';
import CredentialList from 'dogma/features/project/settings/credentials/CredentialList';

const RepositoryCredentialPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const { data: credentialsData } = useGetRepoCredentialsQuery({
    projectName: projectName as string,
    repoName: repoName as string,
  });
  const [deleteCredentialMutation, { isLoading }] = useDeleteRepoCredentialMutation();
  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'credentials'}>
      {() => (
        <>
          <Flex>
            <Spacer />
            <Button
              as={Link}
              href={`/app/projects/${projectName}/repos/${repoName}/settings/credentials/new`}
              size="sm"
              rightIcon={<AiOutlinePlus />}
              colorScheme="teal"
            >
              New Credential
            </Button>
          </Flex>
          <CredentialList
            projectName={projectName}
            repoName={repoName}
            credentials={credentialsData}
            deleteCredential={(projectName, id, repoName) =>
              deleteCredentialMutation({ projectName, id, repoName }).unwrap()
            }
            isLoading={isLoading}
          />
        </>
      )}
    </RepositorySettingsView>
  );
};

export default RepositoryCredentialPage;
