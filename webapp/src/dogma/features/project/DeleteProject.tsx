import {
  Box,
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
import { useDeleteProjectMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/hooks';
import Router from 'next/router';

export const DeleteProject = ({ projectName }: { projectName: string }) => {
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [deleteProject, { isLoading }] = useDeleteProjectMutation();
  const handleDelete = async () => {
    try {
      await deleteProject({ projectName }).unwrap();
      dispatch(
        createMessage({
          title: 'Project deleted.',
          text: `Successfully deleted ${projectName}`,
          type: 'success',
        }),
      );
      onClose();
      Router.push('/app/projects');
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to delete ${projectName}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <>
      <Box>
        <Button colorScheme="red" variant="outline" size="sm" onClick={onToggle}>
          Delete Project
        </Button>
      </Box>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Delete project {`${projectName}`}</ModalBody>
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
