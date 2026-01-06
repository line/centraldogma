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
import { useDeleteAppIdentityMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';

export const DeleteAppIdentity = ({ appId, hidden }: { appId: string; hidden: boolean }) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [deleteAppIdentity, { isLoading }] = useDeleteAppIdentityMutation();
  const handleDelete = async () => {
    try {
      await deleteAppIdentity({ appId }).unwrap();
      dispatch(newNotification('App identity deleted', `Successfully deleted ${appId}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${appId}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button
        size="sm"
        colorScheme="red"
        hidden={hidden}
        variant="ghost"
        onClick={onToggle}
        leftIcon={<MdDelete />}
      >
        Delete
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Delete application identity {`${appId}`}</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="red" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="red" onClick={handleDelete} isLoading={isLoading} loadingText="Deleting">
                Delete
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
