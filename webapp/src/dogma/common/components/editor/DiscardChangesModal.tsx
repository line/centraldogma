import {
  Button,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
} from '@chakra-ui/react';

export const DiscardChangesModal = ({
  isOpen,
  onClose,
  resetViewEditor,
}: {
  isOpen: boolean;
  onClose: () => void;
  resetViewEditor: () => void;
}) => {
  return (
    <Modal isOpen={isOpen} onClose={onClose}>
      <ModalOverlay />
      <ModalContent>
        <ModalHeader>Are you sure?</ModalHeader>
        <ModalCloseButton />
        <ModalBody>Your changes will be discarded!</ModalBody>
        <ModalFooter>
          <Button colorScheme="red" mr={3} onClick={resetViewEditor}>
            Discard changes
          </Button>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
