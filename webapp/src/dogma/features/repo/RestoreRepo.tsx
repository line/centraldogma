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
import { useRestoreRepoMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdOutlineRestore } from 'react-icons/md';

export const RestoreRepo = ({
  projectName,
  repoName,
  hidden,
}: {
  projectName: string;
  repoName: string;
  hidden: boolean;
}) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [restoreRepo, { isLoading }] = useRestoreRepoMutation();
  const handleRestore = async () => {
    try {
      await restoreRepo({ projectName, repoName }).unwrap();
      dispatch(newNotification('Repo restored.', `Successfully restored ${repoName}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to restore ${repoName}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button
        colorScheme="blue"
        leftIcon={<MdOutlineRestore />}
        size="sm"
        variant="ghost"
        onClick={onToggle}
        hidden={hidden}
      >
        Restore
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Restore repository {`${repoName}`}</ModalBody>
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
