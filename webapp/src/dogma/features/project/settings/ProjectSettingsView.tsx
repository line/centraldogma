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

import { Box, Flex, Heading, HStack, Tab, TabList, TabPanel, TabPanels, Tabs } from '@chakra-ui/react';
import { useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import { Deferred } from 'dogma/common/components/Deferred';
import { ReactNode } from 'react';
import { ProjectMetadataDto } from 'dogma/features/project/ProjectMetadataDto';
import Link from 'next/link';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useRouter } from 'next/router';
import { useAppSelector } from 'dogma/hooks';
import { AppMemberDetailDto } from 'dogma/features/project/settings/members/AppMemberDto';
import { FiBox } from 'react-icons/fi';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { HttpStatusCode } from 'dogma/features/api/HttpStatusCode';

interface ProjectSettingsViewProps {
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
  // 'repositories' is the index tab
  { name: 'repositories', path: '', accessRole: 'GUEST' },
  { name: 'permissions', path: 'permissions', accessRole: 'OWNER' },
  { name: 'members', path: 'members', accessRole: 'OWNER' },
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

const ProjectSettingsView = ({ projectName, currentTab, children }: ProjectSettingsViewProps) => {
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
  let queryError = error as FetchBaseQueryError;
  if (queryError?.status == HttpStatusCode.Forbidden) {
    // 403 Forbidden means the user has a GUEST role
    queryError = null;
  } else {
    if (metadata && user) {
      const appUser = Array.from(Object.values(metadata.members)).find(
        (m: AppMemberDetailDto) => m.login === user.email,
      );
      if (appUser != null) {
        accessRole = appUser.role;
      }
    }
  }
  return (
    <Deferred isLoading={isLoading} error={queryError}>
      {() => (
        <Box p="2">
          <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
          <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
            <Heading size="lg">
              <HStack>
                <Box color={'teal'}>
                  <FiBox />
                </Box>
                <Box color={'teal'}>{projectName}</Box>
                <Box>{currentTab}</Box>
              </HStack>
            </Heading>
          </Flex>
          <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
            <TabList>
              {TABS.map((tab) => {
                const allowed = isAllowed(accessRole, tab);
                let link = '';
                if (allowed) {
                  link = `/app/projects/${projectName}/settings`;
                  if (tab.path !== '') {
                    link += `/${tab.path}`;
                  }
                }
                return (
                  <Tab as={Link} key={tab.name} href={link} isDisabled={!allowed}>
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

export default ProjectSettingsView;
