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

import { Tag, TagLabel, TagLeftIcon } from '@chakra-ui/react';
import { ReplicationStatus } from 'dogma/features/repo/RepoDto';
import { GoLock, GoPencil } from 'react-icons/go';

export type RepoStatusTagProps = {
  status?: ReplicationStatus;
};

export const RepoStatusTag = ({ status }: RepoStatusTagProps) => {
  if (!status) {
    return null;
  }
  const readOnly = status === 'READ_ONLY';
  return (
    <Tag borderRadius="full" colorScheme={readOnly ? 'red' : 'green'} variant="subtle" size="sm">
      <TagLeftIcon as={readOnly ? GoLock : GoPencil} />
      <TagLabel>{readOnly ? 'Read-only' : 'Writable'}</TagLabel>
    </Tag>
  );
};
