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
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
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
      dispatch(
        createMessage({
          title: 'Repo restored.',
          text: `Successfully restored ${repoName}`,
          type: 'success',
        }),
      );
      onClose();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to restore ${repoName}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
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