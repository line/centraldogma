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
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useActivateAppIdentityMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { FcIdea } from 'react-icons/fc';

export const ActivateAppIdentity = ({ appId, hidden }: { appId: string; hidden: boolean }) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [activate, { isLoading }] = useActivateAppIdentityMutation();
  const handleActivate = async () => {
    try {
      const response = await activate({ appId }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('App identity activated', `Successfully activated ${appId}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to activate ${appId}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button
        size="sm"
        colorScheme="blue"
        hidden={hidden}
        variant="ghost"
        onClick={onToggle}
        leftIcon={<FcIdea />}
      >
        Activate
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Activate application identity {`${appId}`}</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button
                colorScheme="teal"
                onClick={handleActivate}
                isLoading={isLoading}
                loadingText="Activating"
              >
                Activate
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
