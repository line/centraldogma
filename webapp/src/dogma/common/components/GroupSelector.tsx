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
import {
  Box,
  Button,
  Menu,
  MenuButton,
  MenuDivider,
  MenuItem,
  MenuList,
  Text,
  useColorModeValue,
} from '@chakra-ui/react';
import { ChevronDownIcon } from '@chakra-ui/icons';
import Router from 'next/router';
import { useGetGroupsQuery } from 'dogma/features/xds/xdsApiSlice';

// A dropdown at the top of the xDS sidebar to switch between groups, or jump back to the full groups list.
export const GroupSelector = ({ currentGroup }: { currentGroup?: string }) => {
  const { data: groups, isLoading, isError } = useGetGroupsQuery();
  const labelColor = useColorModeValue('gray.500', 'gray.400');
  const borderColor = useColorModeValue('gray.200', 'gray.600');

  return (
    <Box>
      <Text px={1} mb={1} fontSize="xs" fontWeight="bold" color={labelColor} letterSpacing="wide">
        GROUP
      </Text>
      <Menu matchWidth>
        <MenuButton
          as={Button}
          w="100%"
          variant="outline"
          size="sm"
          textAlign="left"
          justifyContent="space-between"
          borderColor={borderColor}
          rightIcon={<ChevronDownIcon />}
          fontWeight="bold"
        >
          {currentGroup || 'Select a group'}
        </MenuButton>
        <MenuList maxH="320px" overflowY="auto" zIndex="dropdown">
          <MenuItem onClick={() => Router.push('/app/xds')}>All groups</MenuItem>
          <MenuDivider />
          {isLoading ? (
            <MenuItem isDisabled>Loading…</MenuItem>
          ) : isError ? (
            <MenuItem isDisabled>Failed to load groups</MenuItem>
          ) : (
            <>
              {(groups || []).map((group) => (
                <MenuItem
                  key={group.id}
                  fontWeight={group.id === currentGroup ? 'bold' : 'normal'}
                  onClick={() =>
                    Router.push(`/app/xds/group?name=${encodeURIComponent(group.id)}&type=listeners`)
                  }
                >
                  {group.id}
                </MenuItem>
              ))}
              {/* Only shown once the query has resolved successfully with no groups. */}
              {(groups || []).length === 0 && <MenuItem isDisabled>No groups yet</MenuItem>}
            </>
          )}
        </MenuList>
      </Menu>
    </Box>
  );
};
