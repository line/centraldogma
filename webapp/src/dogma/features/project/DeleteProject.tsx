import {
  Box,
  Button,
  Flex,
  HStack,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  Spacer,
  Text,
  useDisclosure,
} from '@chakra-ui/react';
import { useDeleteProjectMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
import Router from 'next/router';
import { FaSkullCrossbones } from 'react-icons/fa';

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
      <Box borderWidth="1px" borderRadius="lg" overflow="hidden" p={4} mt={20}>
        <Flex>
          <Text>Once you delete a project, there is no going back. Please be certain.</Text>
          <Spacer />
          <Button leftIcon={<FaSkullCrossbones />} color="red" onClick={onToggle}>
            Delete Project
          </Button>
        </Flex>
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
