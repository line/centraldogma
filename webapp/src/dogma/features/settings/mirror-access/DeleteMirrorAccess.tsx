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

import { Button, useDisclosure } from '@chakra-ui/react';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';

export const DeleteMirrorAccessControl = ({
  id,
  deleteMirrorAccessControl,
  isLoading,
}: {
  id: string;
  deleteMirrorAccessControl: (id: string) => Promise<void>;
  isLoading: boolean;
}): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteMirrorAccessControl(id);
      dispatch(newNotification('Mirror access control deleted.', `Successfully deleted ${id}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${id}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button leftIcon={<MdDelete />} colorScheme="red" size="sm" onClick={onToggle}>
        Delete
      </Button>
      <DeleteConfirmationModal
        isOpen={isOpen}
        onClose={onClose}
        id={id}
        type={'mirror access control'}
        handleDelete={handleDelete}
        isLoading={isLoading}
      />
    </>
  );
};
