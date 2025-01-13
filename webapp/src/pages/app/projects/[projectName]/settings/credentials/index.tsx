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
import { Button, Flex, Spacer } from '@chakra-ui/react';
import Link from 'next/link';
import { AiOutlinePlus } from 'react-icons/ai';
import React from 'react';
import { useGetCredentialsQuery, useDeleteCredentialMutation } from 'dogma/features/api/apiSlice';
import ProjectSettingsView from 'dogma/features/project/settings/ProjectSettingsView';
import CredentialList from 'dogma/features/project/settings/credentials/CredentialList';

const ProjectCredentialPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const { data: credentialsData } = useGetCredentialsQuery(projectName);
  const [deleteCredentialMutation, { isLoading }] = useDeleteCredentialMutation();

  return (
    <ProjectSettingsView projectName={projectName} currentTab={'credentials'}>
      {() => (
        <>
          <Flex>
            <Spacer />
            <Button
              as={Link}
              href={`/app/projects/${projectName}/settings/credentials/new`}
              size="sm"
              rightIcon={<AiOutlinePlus />}
              colorScheme="teal"
            >
              New Credential
            </Button>
          </Flex>
          <CredentialList
            projectName={projectName}
            credentials={credentialsData}
            deleteCredential={(projectName, id) => deleteCredentialMutation({ projectName, id }).unwrap()}
            isLoading={isLoading}
          />
        </>
      )}
    </ProjectSettingsView>
  );
};

export default ProjectCredentialPage;
