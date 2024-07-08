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
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useUpdateRolePermissionMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import { RepoRolePermissionDto } from 'dogma/features/repo/RepoPermissionDto';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/hooks';

export const ConfirmUpdateRolePermission = ({
  projectName,
  repoName,
  data,
}: {
  projectName: string;
  repoName: string;
  data: RepoRolePermissionDto;
}) => {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [updateRolePermission, { isLoading }] = useUpdateRolePermissionMutation();
  const handleUpdate = async () => {
    try {
      const response = await updateRolePermission({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: 'Repository permissions updated',
          text: `Successfully updated ${repoName}`,
          type: 'success',
        }),
      );
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to update ${repoName}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
    onClose();
  };
  return (
    <>
      <Button type="submit" colorScheme="teal" onClick={onOpen}>
        Save changes
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Update permission of the roles for the repository {repoName}?</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="teal" onClick={handleUpdate} isLoading={isLoading} loadingText="Updating">
                Update
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
