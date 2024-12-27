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
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { MdDelete } from 'react-icons/md';

export const DeleteRepo = ({
  projectName,
  repoName,
  hidden,
  buttonVariant,
  buttonSize,
}: {
  projectName: string;
  repoName: string;
  hidden: boolean;
  buttonVariant: 'solid' | 'outline';
  buttonSize: 'sm' | 'lg';
}) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [deleteRepo, { isLoading }] = useDeleteRepoMutation();
  const handleDelete = async () => {
    try {
      await deleteRepo({ projectName, repoName }).unwrap();
      dispatch(newNotification('Repo deleted.', `Successfully deleted ${repoName}`, 'success'));
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${repoName}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button
        leftIcon={<MdDelete />}
        colorScheme="red"
        variant={buttonVariant}
        size={buttonSize}
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
