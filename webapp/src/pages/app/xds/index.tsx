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
import { Box, Button, Flex, Heading, Spacer } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { TbServer2 } from 'react-icons/tb';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetGroupsQuery } from 'dogma/features/xds/xdsApiSlice';
import { GroupList } from 'dogma/features/xds/GroupList';
import { NewGroup } from 'dogma/features/xds/NewGroup';
import { useAppSelector } from 'dogma/hooks';

const GroupsPage = () => {
  const { user } = useAppSelector((state) => state.auth);
  const { data, isLoading, error } = useGetGroupsQuery(undefined, {
    refetchOnMountOrArgChange: true,
  });
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box p="2">
          <Flex alignItems="center" gap="2" mb={6}>
            <Heading size="lg" color="teal">
              xDS Groups
            </Heading>
            <Spacer />
            {/* The control plane view is system-administrator only, so the entry point is shown only to them. */}
            {user?.systemAdmin && (
              <Button
                as={RouteLink}
                href="/app/xds/control-plane"
                variant="outline"
                colorScheme="teal"
                size="sm"
                leftIcon={<TbServer2 />}
              >
                Control Plane
              </Button>
            )}
            <NewGroup />
          </Flex>
          <GroupList groups={data || []} />
        </Box>
      )}
    </Deferred>
  );
};

export default GroupsPage;
