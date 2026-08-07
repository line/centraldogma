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
import { Box, Button, HStack } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { IoAddCircleOutline } from 'react-icons/io5';
import MirrorList from 'dogma/features/repo/settings/mirrors/MirrorList';

// Displays mirrors configured for the xDS group's backing repository (@xds/{group}).
export const XdsMirroringTab = ({ group }: { group: string }) => (
  <Box>
    <HStack justify="flex-end" mb={4}>
      <Button
        as={RouteLink}
        href={`/app/xds/mirrors/new?group=${encodeURIComponent(group)}`}
        colorScheme="teal"
        size="sm"
        leftIcon={<IoAddCircleOutline />}
      >
        New Mirror
      </Button>
    </HStack>
    <MirrorList
      projectName="@xds"
      repoName={group}
      buildDetailUrl={(id) => `/app/xds/mirrors/${encodeURIComponent(id)}?group=${encodeURIComponent(group)}`}
      hideRepoColumn
    />
  </Box>
);
