import {
  Alert,
  AlertDescription,
  AlertIcon,
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
  UnorderedList,
  ListItem,
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

export type VisibilityChange = 'toPublic' | 'toPrivate' | null;

export const ConfirmUpdateRepositoryProjectRoles = ({
  projectName,
  repoName,
  handleSubmit,
  isDirty,
  reset,
  visibilityChange,
}: {
  projectName: string;
  repoName: string;
  handleSubmit: UseFormHandleSubmit<ProjectRolesDto>;
  isDirty: boolean;
  reset: UseFormReset<ProjectRolesDto>;
  visibilityChange: VisibilityChange;
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
          <ModalHeader>
            {visibilityChange === 'toPublic'
              ? 'Make this repository public?'
              : visibilityChange === 'toPrivate'
                ? 'Make this repository private?'
                : 'Are you sure?'}
          </ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <Box mb={visibilityChange ? 3 : 0}>
              Update project roles for the repository{' '}
              <Box as="span" fontWeight="semibold">
                {projectName}/{repoName}
              </Box>
              ?
            </Box>
            {visibilityChange === 'toPublic' && (
              <Alert status="warning" borderRadius="md" alignItems="flex-start">
                <AlertIcon />
                <AlertDescription fontSize="sm">
                  Everyone who can sign in — and all application tokens and certificates with guest access
                  allowed — will be able to read:
                  <UnorderedList mt={1}>
                    <ListItem>All files and commit history, including diffs</ListItem>
                    <ListItem>The repository over git clone</ListItem>
                    <ListItem>Repository variables</ListItem>
                  </UnorderedList>
                  Write access is never granted to guests.
                </AlertDescription>
              </Alert>
            )}
            {visibilityChange === 'toPrivate' && (
              <Alert status="info" borderRadius="md" alignItems="flex-start">
                <AlertIcon />
                <AlertDescription fontSize="sm">
                  Guest read access will be revoked. Only members and users or app identities granted a role
                  will be able to read this repository.
                </AlertDescription>
              </Alert>
            )}
          </ModalBody>
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
                {visibilityChange === 'toPublic'
                  ? 'Make public'
                  : visibilityChange === 'toPrivate'
                    ? 'Make private'
                    : 'Update'}
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
