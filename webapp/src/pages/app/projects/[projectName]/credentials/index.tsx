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
import ProjectMetadataView from 'dogma/features/metadata/ProjectMetadataView';
import { Button, Flex, Spacer } from '@chakra-ui/react';
import Link from 'next/link';
import { AiOutlinePlus } from 'react-icons/ai';
import CredentialList from 'dogma/features/credential/CredentialList';
import React from 'react';

const ProjectCredentialPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  return (
    <ProjectMetadataView projectName={projectName} currentTab={'credentials'}>
      {() => (
        <>
          <Flex>
            <Spacer />
            <Button
              as={Link}
              href={`/app/projects/${projectName}/credentials/new`}
              size="sm"
              rightIcon={<AiOutlinePlus />}
              colorScheme="teal"
            >
              New Credential
            </Button>
          </Flex>
          <CredentialList projectName={projectName} />
        </>
      )}
    </ProjectMetadataView>
  );
};

export default ProjectCredentialPage;
