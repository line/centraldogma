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

import { Button, useDisclosure } from '@chakra-ui/react';
import { MdLockOpen } from 'react-icons/md';
import { useUpdateRepositoryStatusMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { RepoStatusConfirmModal } from 'dogma/features/settings/repo-status/RepoStatusConfirmModal';

interface MakeWritableProps {
  projectName: string;
  repoName: string;
}

export const MakeWritable = ({ projectName, repoName }: MakeWritableProps): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [updateRepositoryStatus, { isLoading }] = useUpdateRepositoryStatusMutation();

  const handleConfirm = async () => {
    try {
      await updateRepositoryStatus({ projectName, repoName, status: 'WRITABLE' }).unwrap();
      dispatch(
        newNotification('Repository is now writable', `${projectName}/${repoName} is now writable`, 'success'),
      );
      onClose();
    } catch (error) {
      dispatch(
        newNotification(
          `Failed to make ${projectName}/${repoName} writable`,
          ErrorMessageParser.parse(error),
          'error',
        ),
      );
    }
  };

  return (
    <>
      <Button leftIcon={<MdLockOpen />} colorScheme="green" variant="outline" size="sm" onClick={onToggle}>
        Make writable
      </Button>
      <RepoStatusConfirmModal
        isOpen={isOpen}
        onClose={onClose}
        projectName={projectName}
        repoName={repoName}
        targetStatus="WRITABLE"
        onConfirm={handleConfirm}
        isLoading={isLoading}
      />
    </>
  );
};
