import {
  Button,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  useDisclosure,
} from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import { useDeactivateTokenMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
import { FcNoIdea } from 'react-icons/fc';

export const DeactivateToken = ({ appId, hidden }: { appId: string; hidden: boolean }) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [deactivate, { isLoading }] = useDeactivateTokenMutation();
  const handleDeactivate = async () => {
    try {
      const response = await deactivate({ appId }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: 'Token deactivated',
          text: `Successfully deactivated ${appId}`,
          type: 'success',
        }),
      );
      onClose();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to deactivate ${appId}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <>
      <Button size="sm" hidden={hidden} variant="ghost" onClick={onToggle} leftIcon={<FcNoIdea />}>
        Deactivate
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Deactivate application token {`${appId}`}</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="red" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button
                colorScheme="red"
                onClick={handleDeactivate}
                isLoading={isLoading}
                loadingText="Deactivating"
              >
                Deactivate
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};