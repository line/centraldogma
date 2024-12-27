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
import { useAppSelector } from 'dogma/hooks';
import { useDeleteMemberMutation } from 'dogma/features/api/apiSlice';
import ProjectSettingsView from 'dogma/features/project/settings/ProjectSettingsView';
import { AddMember } from 'dogma/features/project/settings/members/AddMember';
import AppEntityList from 'dogma/features/project/settings/AppEntityList';

const ProjectMemberPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  const [deleteMember, { isLoading }] = useDeleteMemberMutation();
  const auth = useAppSelector((state) => state.auth);

  return (
    <ProjectSettingsView projectName={projectName} currentTab={'members'}>
      {(metadata) => (
        <>
          <Flex>
            <Spacer />
            <AddMember projectName={projectName} />
          </Flex>
          <AppEntityList
            data={Array.from(Object.values(metadata.members))}
            projectName={projectName}
            entityType={'member'}
            getId={(row) => row.login}
            getRole={(row) => row.role}
            getAddedBy={(row) => row.creation.user}
            getTimestamp={(row) => row.creation.timestamp}
            deleteMutation={(projectName, id) => deleteMember({ projectName, id }).unwrap()}
            showDeleteButton={(row) => row.login !== auth.user?.email}
            isLoading={isLoading}
          />
        </>
      )}
    </ProjectSettingsView>
  );
};

export default ProjectMemberPage;
