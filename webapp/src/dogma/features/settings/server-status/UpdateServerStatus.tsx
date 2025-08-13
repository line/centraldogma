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

import {
  Button,
  Code,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  useDisclosure,
  VStack,
} from '@chakra-ui/react';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useUpdateServerStatusMutation } from 'dogma/features/api/apiSlice';
import { useAppDispatch } from 'dogma/hooks';
import { ServerStatusScope, ServerStatusType } from 'dogma/features/settings/server-status/ServerStatusDto';

interface UpdateServerStatusProps {
  currentStatus: ServerStatusType;
  selectedStatus: ServerStatusType;
  selectedScope: ServerStatusScope;
  getStatusColorScheme: (status: ServerStatusType) => string;
}

export const UpdateServerStatus = ({
  currentStatus,
  selectedStatus,
  selectedScope,
  getStatusColorScheme,
}: UpdateServerStatusProps) => {
  const dispatch = useAppDispatch();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const [updateServerStatus, { isLoading: isUpdating }] = useUpdateServerStatusMutation();

  const handleUpdate = async () => {
    if (selectedStatus === undefined) {
      dispatch(newNotification('No status selected', 'Please select a new server status to apply.', 'warning'));
      return;
    }

    try {
      await updateServerStatus({
        serverStatus: selectedStatus,
        scope: selectedScope,
      }).unwrap();

      dispatch(
        newNotification(
          'Server status updated!',
          `Status changed to ${selectedStatus} with scope ${selectedScope}.`,
          'success',
        ),
      );
      onClose();
    } catch (error) {
      dispatch(newNotification('Failed to update server status.', ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <>
      <Button
        colorScheme="blue"
        onClick={onToggle}
        loadingText="Updating..."
        isDisabled={
          !selectedStatus || (currentStatus && currentStatus === selectedStatus && selectedScope === 'ALL')
        }
      >
        Update Server Status
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <p>Update server status</p>
            <HStack align="start" spacing={4} mt={2}>
              <VStack align="start" spacing={1}>
                <HStack>
                  <Code colorScheme={getStatusColorScheme(currentStatus)}>{currentStatus}</Code>
                  <span>→</span>
                  <Code colorScheme={getStatusColorScheme(selectedStatus)}>{selectedStatus}</Code>
                </HStack>
                <HStack>
                  <span>scope: {selectedScope}</span>
                </HStack>
              </VStack>
            </HStack>
          </ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="blue" onClick={handleUpdate} isLoading={isUpdating} loadingText="Updating">
                Update
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
