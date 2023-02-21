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
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
import { MdDelete } from 'react-icons/md';

export const DeleteMember = ({
  projectName,
  repoName,
  id,
  deleteMember,
  isLoading,
}: {
  projectName: string;
  repoName?: string;
  id: string;
  deleteMember: (projectName: string, id: string, repoName?: string) => Promise<void>;
  isLoading: boolean;
}): JSX.Element => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const handleDelete = async () => {
    try {
      await deleteMember(projectName, id, repoName);
      dispatch(
        createMessage({
          title: 'Member deleted.',
          text: `Successfully deleted ${id}`,
          type: 'success',
        }),
      );
      onClose();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to delete ${id}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <>
      <Button leftIcon={<MdDelete />} colorScheme="red" size="sm" variant="ghost" onClick={onToggle}>
        Delete
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            Delete {id} from {projectName}?
          </ModalBody>
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
