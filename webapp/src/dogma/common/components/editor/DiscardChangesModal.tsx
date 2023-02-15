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
          <HStack spacing={3}>
            <Button colorScheme="red" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button colorScheme="red" onClick={resetViewEditor}>
              Discard changes
            </Button>
          </HStack>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
};
