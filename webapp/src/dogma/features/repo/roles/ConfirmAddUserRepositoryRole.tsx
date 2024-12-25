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
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { AddUserRepositoryRoleDto } from 'dogma/features/repo/roles/AddUserRepositoryRoleDto';
import { RepositoryRole } from '../RepositoriesMetadataDto';

export const ConfirmAddUserRepositoryRole = ({
  projectName,
  repoName,
  loginId,
  repositoryRole,
  isOpen,
  onClose,
  resetForm,
  addUserRepositoryRole,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  loginId: string;
  repositoryRole: string;
  isOpen: boolean;
  onClose: () => void;
  resetForm: () => void;
  addUserRepositoryRole: ApiAction<AddUserRepositoryRoleDto, void>;
  isLoading: boolean;
}) => {
  const dispatch = useAppDispatch();
  const data = {
    id: loginId,
    role: repositoryRole as RepositoryRole,
  };

  const handleUpdate = async () => {
    try {
      const response = await addUserRepositoryRole({ projectName, repoName, data }).unwrap();
      if ((response as unknown as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as unknown as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('Repository user role is added', `Successfully updated ${repoName}`, 'success'));
    } catch (error) {
      dispatch(newNotification(`Failed to update ${repoName}`, ErrorMessageParser.parse(error), 'error'));
    }
    onClose();
    resetForm();
  };
  return (
    <>
      <Button type="submit" colorScheme="teal">
        Save changes
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Repository {repoName}</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Add a member {loginId}?</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="teal" onClick={handleUpdate} isLoading={isLoading} loadingText="Adding">
                Add
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
