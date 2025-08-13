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

import { useState } from 'react';
import { Badge, Box, Code, Flex, Radio, RadioGroup, Select, Spacer, Stack, Text, } from '@chakra-ui/react';
import SettingView from 'dogma/features/settings/SettingView';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetServerStatusQuery, } from 'dogma/features/api/apiSlice';
import { ServerStatusScope, ServerStatusType, } from 'dogma/features/settings/server-status/ServerStatusDto';
import { UpdateServerStatus } from 'dogma/features/settings/server-status/UpdateServerStatus';

const ServerStatusPage = () => {
  const { data: currentStatus, error, isLoading } = useGetServerStatusQuery();

  const [selectedStatus, setSelectedStatus] = useState<ServerStatusType | undefined>(undefined);
  const [selectedScope, setSelectedScope] = useState<ServerStatusScope>('ALL');

  const getStatusColorScheme = (status: ServerStatusType) => {
    switch (status) {
      case 'WRITABLE':
        return 'green';
      case 'REPLICATION_ONLY':
        return 'yellow';
      case 'READ_ONLY':
        return 'red';
    }
  };

  const getStatusBadge = (status: ServerStatusType | undefined) => {
    if (!status) {
      return <Text ml="4">Not available</Text>;
    }

    return (
      <Badge ml="4" px="3" py="1" fontSize="md" colorScheme={getStatusColorScheme(status)} borderRadius="md">
        {status}
      </Badge>
    );
  };

  return (
    <SettingView currentTab="Server Status">
      <Deferred isLoading={isLoading} error={error}>
        {() => (
          <Box p="4">
            <Flex mb="6" alignItems="center">
              <Text fontSize="lg" fontWeight="bold">
                Current Server Status:
              </Text>
              {getStatusBadge(currentStatus)}
              <Spacer />
            </Flex>

            <Box mb="6">
              <Text fontSize="lg" fontWeight="bold" mb="3">
                Select New Server Status:
              </Text>
              <RadioGroup onChange={(val: ServerStatusType) => setSelectedStatus(val)} value={selectedStatus}>
                <Stack direction={{ base: 'column', md: 'row' }} spacing="4">
                  <Radio value="WRITABLE">Writable (Writable & Replicating)</Radio>
                  <Radio value="REPLICATION_ONLY">Replication Only (Not Writable & Replicating)</Radio>
                  <Radio value="READ_ONLY">Read Only (Not Writable & Not Replicating)</Radio>
                </Stack>
              </RadioGroup>
            </Box>

            <Box mb="6">
              <Text fontSize="lg" fontWeight="bold" mb="3">
                Apply Scope:
              </Text>
              <Select
                value={selectedScope}
                onChange={(e) => setSelectedScope(e.target.value as ServerStatusScope)}
                width={{ base: '100%', md: '300px' }}
              >
                <option value="ALL">ALL (Propagate to all cluster servers)</option>
                <option value="LOCAL">LOCAL (Apply only to this server)</option>
              </Select>
              <Text fontSize="sm" color="gray.500" mt="1">
                Note: Setting &#39;Replication Only&#39; or &#39;Writable&#39; with{' '}
                <Code variant="outline">ALL</Code> scope is not allowed if the cluster is currently not
                replicating. Use <Code variant="outline">LOCAL</Code> scope for individual instance changes.
              </Text>
            </Box>

            <UpdateServerStatus
              currentStatus={currentStatus}
              selectedStatus={selectedStatus}
              selectedScope={selectedScope}
              getStatusColorScheme={getStatusColorScheme}
            />
          </Box>
        )}
      </Deferred>
    </SettingView>
  );
};

export default ServerStatusPage;
