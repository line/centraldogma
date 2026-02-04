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
import Link from 'next/link';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useRouter } from 'next/router';
import { useAppSelector } from 'dogma/hooks';
import { GoRepo } from 'react-icons/go';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { HttpStatusCode } from 'dogma/features/api/HttpStatusCode';
import { findUserRepositoryRole, RepositoryRole } from 'dogma/features/auth/RepositoryRole';
import { ProjectMetadataDto } from 'dogma/features/project/ProjectMetadataDto';

interface RepositorySettingsViewProps {
  projectName: string;
  repoName: string;
  currentTab: TabName;
  children?: (meta: ProjectMetadataDto) => ReactNode;
}

type TabName = 'users' | 'roles' | 'App Identities' | 'mirrors' | 'credentials' | 'variables' | 'Danger Zone';

export interface TapInfo {
  name: TabName;
  path: string;
  accessRole: RepositoryRole;
  allowAnonymous: boolean;
}

const TABS: TapInfo[] = [
  // 'roles' is the index tab
  { name: 'roles', path: '', accessRole: 'ADMIN', allowAnonymous: false },
  { name: 'users', path: 'users', accessRole: 'ADMIN', allowAnonymous: false },
  { name: 'App Identities', path: 'app-identities', accessRole: 'ADMIN', allowAnonymous: false },
  { name: 'mirrors', path: 'mirrors', accessRole: 'ADMIN', allowAnonymous: true },
  { name: 'credentials', path: 'credentials', accessRole: 'ADMIN', allowAnonymous: true },
  { name: 'variables', path: 'variables', accessRole: 'WRITE', allowAnonymous: true },
  { name: 'Danger Zone', path: 'danger-zone', accessRole: 'ADMIN', allowAnonymous: true },
];

function isAllowed(userRepositoryRole: string, anonymous: boolean, tabInfo: TapInfo): boolean {
  if (!tabInfo) {
    return false;
  }
  if (anonymous && tabInfo.allowAnonymous) {
    return true;
  }

  switch (tabInfo.accessRole) {
    case 'ADMIN':
      return userRepositoryRole === 'ADMIN';
    case 'WRITE':
      return userRepositoryRole === 'ADMIN' || userRepositoryRole === 'WRITE';
    case 'READ':
      return userRepositoryRole === 'ADMIN' || userRepositoryRole === 'WRITE' || userRepositoryRole === 'READ';
  }
}

const RepositorySettingsView = ({
  projectName,
  repoName,
  currentTab,
  children,
}: RepositorySettingsViewProps) => {
  const { user, isInAnonymousMode } = useAppSelector((state) => state.auth);
  const tabIndex = TABS.findIndex((tab) => tab.name === currentTab);
  const router = useRouter();

  const {
    data: projectMetadata = {} as ProjectMetadataDto,
    isLoading,
    error,
  } = useGetMetadataByProjectNameQuery(projectName, {
    refetchOnFocus: true,
    skip: false,
  });

  let accessRole = null;
  let queryError = error as FetchBaseQueryError;
  if (queryError?.status == HttpStatusCode.Forbidden) {
    // 403 Forbidden means the user has a GUEST role
    queryError = null;
  } else {
    accessRole = findUserRepositoryRole(repoName, user, projectMetadata);
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
                  <GoRepo />
                </Box>
                <Box color={'teal'}>{repoName}</Box>
                <Box>{currentTab}</Box>
              </HStack>
            </Heading>
          </Flex>
          <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
            <TabList>
              {TABS.map((tab) => {
                const allowed = isAllowed(accessRole, isInAnonymousMode, tab);
                let link = '';
                if (allowed) {
                  link = `/app/projects/${projectName}/repos/${repoName}/settings`;
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
                const allowed = isAllowed(accessRole, isInAnonymousMode, tab);
                return (
                  <TabPanel key={tab.name}>
                    {tab.name === currentTab && allowed && children(projectMetadata)}
                  </TabPanel>
                );
              })}
            </TabPanels>
          </Tabs>
        </Box>
      )}
    </Deferred>
  );
};

export default RepositorySettingsView;
