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
import { useDeleteAppIdentityMemberMutation } from 'dogma/features/api/apiSlice';
import ProjectSettingsView from 'dogma/features/project/settings/ProjectSettingsView';
import { AddAppIdentity } from 'dogma/features/project/settings/app-identities/AddAppIdentity';
import AppEntityList from 'dogma/features/project/settings/AppEntityList';

const ProjectAppIdentityPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const [deleteAppIdentity, { isLoading }] = useDeleteAppIdentityMemberMutation();
  return (
    <ProjectSettingsView projectName={projectName} currentTab={'App Identities'}>
      {(metadata) => (
        <>
          <Flex>
            <Spacer />
            <AddAppIdentity projectName={projectName} />
          </Flex>
          <AppEntityList
            data={metadata ? Array.from(Object.values(metadata.appIds)) : []}
            projectName={projectName}
            entityType={'appIdentity'}
            getId={(row) => row.appId}
            getRole={(row) => row.role}
            getAddedBy={(row) => row.creation.user}
            getTimestamp={(row) => row.creation.timestamp}
            deleteMutation={(projectName, id) => deleteAppIdentity({ projectName, id }).unwrap()}
            isLoading={isLoading}
          />
        </>
      )}
    </ProjectSettingsView>
  );
};

export default ProjectAppIdentityPage;
