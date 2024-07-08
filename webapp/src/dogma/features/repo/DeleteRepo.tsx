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
import { useDeleteRepoMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';

export const DeleteRepo = ({
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
  const [deleteRepo, { isLoading }] = useDeleteRepoMutation();
  const handleDelete = async () => {
    try {
      await deleteRepo({ projectName, repoName }).unwrap();
      dispatch(
        createMessage({
          title: 'Repo deleted.',
          text: `Successfully deleted ${repoName}`,
          type: 'success',
        }),
      );
      onClose();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to delete ${repoName}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <>
      <Button
        leftIcon={<MdDelete />}
        colorScheme="red"
        size="sm"
        variant="ghost"
        onClick={onToggle}
        hidden={hidden}
      >
        Delete
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Delete repository {`${repoName}`}</ModalBody>
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
