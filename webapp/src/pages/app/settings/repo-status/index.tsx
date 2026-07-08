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

import { Badge, Box, Flex, Text } from '@chakra-ui/react';
import SettingView from 'dogma/features/settings/SettingView';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetReadOnlyReposQuery, useGetServerStatusQuery } from 'dogma/features/api/apiSlice';
import { ServerStatusType } from 'dogma/features/settings/server-status/ServerStatusDto';
import RepoStatusList from 'dogma/features/settings/repo-status/RepoStatusList';
import MakeReadOnlyForm from 'dogma/features/settings/repo-status/MakeReadOnlyForm';

const getServerStatusColorScheme = (status: ServerStatusType) => {
  switch (status) {
    case 'WRITABLE':
      return 'green';
    case 'REPLICATION_ONLY':
      return 'yellow';
    case 'READ_ONLY':
      return 'red';
  }
};

const RepoStatusPage = () => {
  const { data: serverStatus } = useGetServerStatusQuery();
  const { data: readOnlyRepos, error, isLoading } = useGetReadOnlyReposQuery();

  return (
    <SettingView currentTab="Repository Status">
      <Deferred isLoading={isLoading} error={error}>
        {() => (
          <Box p="4">
            <Flex mb="6" alignItems="center">
              <Text fontSize="lg" fontWeight="bold">
                Current Server Status:
              </Text>
              {serverStatus ? (
                <Badge
                  ml="4"
                  px="3"
                  py="1"
                  fontSize="md"
                  colorScheme={getServerStatusColorScheme(serverStatus)}
                  borderRadius="md"
                >
                  {serverStatus}
                </Badge>
              ) : (
                <Text ml="4">Not available</Text>
              )}
            </Flex>

            <MakeReadOnlyForm />

            <Text fontSize="lg" fontWeight="bold" mb="3">
              Read-only Projects &amp; Repositories:
            </Text>
            {readOnlyRepos && readOnlyRepos.length > 0 ? (
              <RepoStatusList data={readOnlyRepos} />
            ) : serverStatus && serverStatus !== 'WRITABLE' ? (
              <Text color="orange.500">
                The entire server is currently in {serverStatus} mode, so all repositories are read-only.
              </Text>
            ) : (
              <Text color="gray.500">All projects and repositories are writable.</Text>
            )}
          </Box>
        )}
      </Deferred>
    </SettingView>
  );
};

export default RepoStatusPage;
