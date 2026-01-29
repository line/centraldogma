/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import { useDeleteVariableMutation, useGetVariablesQuery } from 'dogma/features/api/apiSlice';
import VariableList from 'dogma/features/project/settings/variables/VariableList';
import RepositorySettingsView from 'dogma/features/repo/settings/RepositorySettingsView';

const RepositoryVariablePage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const repoName = router.query.repoName ? (router.query.repoName as string) : '';
  const { data: variablesData } = useGetVariablesQuery({ projectName, repoName });
  const [deleteVariableMutation, { isLoading }] = useDeleteVariableMutation();

  return (
    <RepositorySettingsView projectName={projectName} repoName={repoName} currentTab={'variables'}>
      {() => (
        <>
          <Flex>
            <Spacer />
            <Button
              as={Link}
              href={`/app/projects/${projectName}/repos/${repoName}/settings/variables/new`}
              size="sm"
              rightIcon={<AiOutlinePlus />}
              colorScheme="teal"
            >
              New Variable
            </Button>
          </Flex>
          <VariableList
            projectName={projectName}
            repoName={repoName}
            variables={variablesData}
            deleteVariable={(projectName, id, repoName) =>
              deleteVariableMutation({ projectName, repoName, id }).unwrap()
            }
            isLoading={isLoading}
          />
        </>
      )}
    </RepositorySettingsView>
  );
};

export default RepositoryVariablePage;
