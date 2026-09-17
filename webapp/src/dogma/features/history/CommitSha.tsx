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

import { Box, Code, HStack, IconButton, Text, Tooltip } from '@chakra-ui/react';
import { MdContentCopy } from 'react-icons/md';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';

export type CommitShaProps = {
  label: string;
  sha: string;
  copyValue?: string;
  abbreviated?: boolean;
};

export const CommitSha = ({ label, sha, copyValue, abbreviated = true }: CommitShaProps) => {
  const dispatch = useAppDispatch();
  const copied = copyValue ?? sha;
  const copyLabel = copyValue ? 'Spring label' : `${label} SHA`;
  return (
    <HStack spacing={1} maxWidth="100%" align="flex-start">
      <Text fontSize="sm" color="gray.500" flexShrink={0}>
        {label}
      </Text>
      <Tooltip label={sha}>
        <Code fontSize="sm">
          {abbreviated ? (
            sha.substring(0, 7)
          ) : (
            <>
              <Box as="span" display={{ base: 'none', lg: 'inline' }}>
                {sha}
              </Box>
              <Box as="span" display={{ base: 'inline', lg: 'none' }} aria-label={sha}>
                {sha.substring(0, 7)}
              </Box>
            </>
          )}
        </Code>
      </Tooltip>
      <Tooltip label={`Copy ${copyLabel}`}>
        <IconButton
          aria-label={`Copy ${copyLabel}`}
          icon={<MdContentCopy />}
          size="xs"
          variant="ghost"
          flexShrink={0}
          onClick={async () => {
            await navigator.clipboard.writeText(copied);
            dispatch(newNotification('', `${copyLabel} copied to clipboard`, 'success'));
          }}
        />
      </Tooltip>
    </HStack>
  );
};
