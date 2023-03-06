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

import { Box, Flex, Heading, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { useAppSelector } from 'dogma/store';
import { useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import { ReactNode } from 'react';
import { ProjectMetadataDto } from 'dogma/features/project/ProjectMetadataDto';
import Link from 'next/link';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useRouter } from 'next/router';
import { AppMemberDetailDto } from 'dogma/features/metadata/AppMemberDto';

interface ProjectMetadataViewProps {
  projectName: string;
  currentTab: TabName;
  children: (meta: ProjectMetadataDto) => ReactNode;
}

type TabName = 'repositories' | 'permissions' | 'members' | 'tokens' | 'mirrors' | 'credentials';

export interface TapInfo {
  name: TabName;
  path: string;
  accessRole: 'OWNER' | 'MEMBER' | 'GUEST';
}

const TABS: TapInfo[] = [
  { name: 'repositories', path: `/metadata`, accessRole: 'GUEST' },
  { name: 'permissions', path: `/permissions`, accessRole: 'OWNER' },
  { name: 'members', path: `members`, accessRole: 'OWNER' },
  { name: 'tokens', path: 'tokens', accessRole: 'OWNER' },
  { name: 'mirrors', path: 'mirrors', accessRole: 'OWNER' },
  { name: 'credentials', path: 'credentials', accessRole: 'OWNER' },
];

function isAllowed(userRole: string, tabInfo: TapInfo): boolean {
  switch (tabInfo.accessRole) {
    case 'OWNER':
      return userRole === 'OWNER';
    case 'MEMBER':
      return userRole === 'OWNER' || userRole === 'MEMBER';
    case 'GUEST':
      return true;
  }
}

const ProjectMetadataView = ({ projectName, currentTab, children }: ProjectMetadataViewProps) => {
  const { user } = useAppSelector((state) => state.auth);
  const tabIndex = TABS.findIndex((tab) => tab.name === currentTab);
  const router = useRouter();

  const {
    data: metadata,
    isLoading,
    error,
  } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
    skip: false,
  });

  let accessRole = 'GUEST';
  if (metadata && user) {
    const appUser = Array.from(Object.values(metadata.members)).find((m: AppMemberDetailDto) => m.login === user.email);
    if (appUser != null) {
      accessRole = appUser.role;
    }
  }
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
          {/*<Breadcrumbs />*/}
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">Project {projectName} - Metadata</Heading>
          </Flex>
          <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
            <TabList>
              {TABS.map((tab) => {
                const allowed = isAllowed(accessRole, tab);
                return (
                  <Tab
                    as={Link}
                    key={tab.name}
                    href={allowed ? `/app/projects/${projectName}/${tab.path}` : ''}
                    isDisabled={!allowed}
                  >
                    <Heading size="sm">
                      {tab.name.charAt(0).toUpperCase()}
                      {tab.name.slice(1)}
                    </Heading>
                  </Tab>
                );
              })}
            </TabList>
            <TabPanels>
              {TABS.map((tab) => {
                const allowed = isAllowed(accessRole, tab);
                return (
                  <TabPanel key={tab.name}>{tab.name === currentTab && allowed && children(metadata)}</TabPanel>
                );
              })}
            </TabPanels>
          </Tabs>
        </Box>
      )}
    </Deferred>
  );
};

export default ProjectMetadataView;
