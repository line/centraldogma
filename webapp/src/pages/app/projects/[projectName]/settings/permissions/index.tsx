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
import RepoPermissionList from 'dogma/features/repo/RepoPermissionList';
import ProjectSettingsView from 'dogma/features/project/settings/ProjectSettingsView';

const ProjectPermissionPage = () => {
  const router = useRouter();
  const projectName = router.query.projectName ? (router.query.projectName as string) : '';
  return (
    <ProjectSettingsView projectName={projectName} currentTab={'permissions'}>
      {(metadata) => (
        <RepoPermissionList
          data={Array.from(Object.values(metadata.repos).filter((repo) => !repo.removal))}
          projectName={projectName}
          members={Array.from(Object.values(metadata.members))}
        />
      )}
    </ProjectSettingsView>
  );
};

export default ProjectPermissionPage;
