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
import {
  Badge,
  Box,
  Button,
  Flex,
  Radio,
  RadioGroup,
  Select,
  Spacer,
  Stack,
  Text,
  useToast,
} from '@chakra-ui/react';
import SettingView from 'dogma/features/settings/SettingView';
import { Deferred } from 'dogma/common/components/Deferred';
import {
  ServerStatusDto,
  ServerStatusScope,
  useGetServerStatusQuery,
  useUpdateServerStatusMutation,
} from 'dogma/features/api/apiSlice';

const ServerStatusPage = () => {
  const toast = useToast();
  const { data: currentStatus, error, isLoading, refetch } = useGetServerStatusQuery();
  const [updateServerStatus, { isLoading: isUpdating }] = useUpdateServerStatusMutation();

  const [selectedStatus, setSelectedStatus] = useState<ServerStatusDto | undefined>(undefined);
  const [selectedScope, setSelectedScope] = useState<ServerStatusScope>('ALL');

  const handleUpdate = async () => {
    if (selectedStatus === undefined) {
      toast({
        title: 'No status selected',
        description: 'Please select a new server status to apply.',
        status: 'warning',
        duration: 3000,
        isClosable: true,
      });
      return;
    }

    try {
      await updateServerStatus({
        serverStatus: selectedStatus,
        scope: selectedScope,
      }).unwrap();
      toast({
        title: 'Server status updated!',
        description: `Status changed to ${selectedStatus} with scope ${selectedScope}.`,
        status: 'success',
        duration: 3000,
        isClosable: true,
      });
      refetch(); // Refetch the status to show the latest
    } catch (err: any) {
      const errorMessage = err.data?.message || err.error || 'An unknown error occurred.';
      toast({
        title: 'Failed to update server status.',
        description: errorMessage,
        status: 'error',
        duration: 5000,
        isClosable: true,
      });
    }
  };

  const getStatusBadgeColor = (status: ServerStatusDto) => {
    switch (status) {
      case 'WRITABLE':
        return 'green';
      case 'REPLICATION_ONLY':
        return 'yellow';
      case 'READ_ONLY':
        return 'red';
      default:
        return 'gray';
    }
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
              {currentStatus ? (
                <Badge
                  ml="4"
                  px="3"
                  py="1"
                  fontSize="md"
                  colorScheme={getStatusBadgeColor(currentStatus)}
                  borderRadius="md"
                >
                  {currentStatus.replace(/_/g, ' ')}
                </Badge>
              ) : (
                <Text ml="4">Not available</Text>
              )}
              <Spacer />
            </Flex>

            <Box mb="6">
              <Text fontSize="lg" fontWeight="bold" mb="3">
                Select New Server Status:
              </Text>
              <RadioGroup onChange={(val: ServerStatusDto) => setSelectedStatus(val)} value={selectedStatus}>
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
                Note: Setting &#39;Replication Only&#39; or &#39;Writable&#39; with &#39;ALL&#39; scope is not
                allowed if the cluster is currently not replicating. Use &#39;LOCAL&#39; scope for individual
                instance changes.
              </Text>
            </Box>

            <Button
              colorScheme="blue"
              onClick={handleUpdate}
              isLoading={isUpdating}
              loadingText="Updating..."
              isDisabled={
                !selectedStatus ||
                (currentStatus && currentStatus === selectedStatus && selectedScope === 'ALL')
              }
            >
              Update Server Status
            </Button>
          </Box>
        )}
      </Deferred>
    </SettingView>
  );
};

export default ServerStatusPage;
