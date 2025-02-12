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
import { useUpdateRepositoryProjectRolesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { ProjectRolesDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { UseFormHandleSubmit, UseFormReset } from 'react-hook-form';

export const ConfirmUpdateRepositoryProjectRoles = ({
  projectName,
  repoName,
  handleSubmit,
  isDirty,
  reset,
}: {
  projectName: string;
  repoName: string;
  handleSubmit: UseFormHandleSubmit<ProjectRolesDto>;
  isDirty: boolean;
  reset: UseFormReset<ProjectRolesDto>;
}) => {
  const { isOpen, onOpen, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const [updateRepositoryProjectRoles, { isLoading }] = useUpdateRepositoryProjectRolesMutation();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const onSubmit = async (data: any) => {
    try {
      const normalizedData = {
        member: data.member === 'NONE' ? null : data.member,
        guest: data.guest === 'NONE' ? null : data.guest,
      };
      const response = await updateRepositoryProjectRoles({
        projectName,
        repoName,
        data: normalizedData,
      }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        newNotification('Repository project roles updated', `Successfully updated ${repoName}`, 'success'),
      );
      reset(data);
    } catch (error) {
      dispatch(newNotification(`Failed to update ${repoName}`, ErrorMessageParser.parse(error), 'error'));
    }
    onClose();
  };
  return (
    <>
      <Button colorScheme="teal" onClick={onOpen} isDisabled={!isDirty}>
        Save changes
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>Update project roles for the repository {repoName}?</ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button
                type="submit"
                colorScheme="teal"
                isLoading={isLoading}
                loadingText="Updating"
                onClick={() => handleSubmit(onSubmit)()}
              >
                Update
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
