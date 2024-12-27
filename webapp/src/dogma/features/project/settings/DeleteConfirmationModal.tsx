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
} from '@chakra-ui/react';

interface DeleteConfirmationModalProps {
  isOpen: boolean;
  onClose: () => void;
  type: string;
  id: string;
  projectName: string;
  repoName?: string;
  handleDelete: () => void;
  isLoading: boolean;
}

export const DeleteConfirmationModal = ({
  isOpen,
  onClose,
  id,
  type,
  projectName,
  repoName,
  handleDelete,
  isLoading,
}: DeleteConfirmationModalProps): JSX.Element => {
  return (
    <Modal isOpen={isOpen} onClose={onClose}>
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>Are you sure?</ModalHeader>
        <ModalCloseButton />
        <ModalBody>
          Delete {type} &apos;{id}&apos; from {repoName ? repoName : projectName}?
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
  );
};
