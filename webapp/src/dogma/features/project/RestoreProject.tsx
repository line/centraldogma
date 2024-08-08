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
import { useRestoreProjectMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdOutlineRestore } from 'react-icons/md';

export const RestoreProject = ({ projectName }: { projectName: string }) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [restoreProject, { isLoading }] = useRestoreProjectMutation();
  const handleRestore = async () => {
    try {
      await restoreProject({ projectName }).unwrap();
      dispatch(newNotification('Project restored.', `Successfully restored ${projectName}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to restore ${projectName}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button colorScheme="blue" leftIcon={<MdOutlineRestore />} size="sm" variant="ghost" onClick={onToggle}>
        Restore
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Restore Project {`${projectName}`}</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="teal" onClick={handleRestore} isLoading={isLoading} loadingText="Restoring">
                Restore
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
