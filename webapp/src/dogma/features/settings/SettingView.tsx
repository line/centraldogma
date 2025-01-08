/*
 * Copyright 2025 LINE Corporation
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
import { ReactNode } from 'react';
import Link from 'next/link';
import { Breadcrumbs } from 'dogma/common/components/Breadcrumbs';
import { useRouter } from 'next/router';
import { useAppSelector } from 'dogma/hooks';
import { GrSystem } from 'react-icons/gr';

interface SettingsViewProps {
  currentTab: TabName;
  children: ReactNode;
}

type TabName = 'Mirror Access Control' | 'Application Tokens';

export interface TapInfo {
  name: TabName;
  path: string;
  admin: boolean;
}

const TABS: TapInfo[] = [
  { name: 'Application Tokens', path: 'tokens', admin: false },
  { name: 'Mirror Access Control', path: 'mirror-access', admin: true },
];

const SettingView = ({ currentTab, children }: SettingsViewProps) => {
  const { user } = useAppSelector((state) => state.auth);
  const tabIndex = TABS.findIndex((tab) => tab.name === currentTab);
  const router = useRouter();

  return (
    <Box>
      <Breadcrumbs path={router.asPath} omitIndexList={[0]} />
      <Flex minWidth="max-content" alignItems="center" gap="2" mb={6}>
        <Heading size="lg">
          <HStack>
            <Box color={'darkred'}>
              <GrSystem />
            </Box>
            <Box>{currentTab}</Box>
          </HStack>
        </Heading>
      </Flex>
      <Tabs variant="enclosed-colored" size="lg" index={tabIndex}>
        <TabList>
          {TABS.map((tab) => {
            console.log('tab', tab);
            if (tab.admin && !user?.systemAdmin) {
              return null;
            }
            let link = `/app/settings`;
            if (tab.path !== '') {
              link += `/${tab.path}`;
            }
            return (
              <Tab as={Link} key={tab.name} href={link}>
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
            return <TabPanel key={tab.name}>{tab.name === currentTab && children}</TabPanel>;
          })}
        </TabPanels>
      </Tabs>
    </Box>
  );
};

export default SettingView;
