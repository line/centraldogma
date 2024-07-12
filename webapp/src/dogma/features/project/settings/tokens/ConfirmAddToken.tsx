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
  Text,
} from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useAddNewTokenMemberMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';

export const ConfirmAddToken = ({
  projectName,
  id,
  role,
  isOpen,
  onClose,
  resetForm,
}: {
  projectName: string;
  id: string;
  role: string;
  isOpen: boolean;
  onClose: () => void;
  resetForm: () => void;
}) => {
  const dispatch = useAppDispatch();
  const [addNewToken, { isLoading }] = useAddNewTokenMemberMutation();
  const handleAddNewToken = async () => {
    try {
      const response = await addNewToken({ projectName, id, role }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('New token saved', `Successfully added ${id}`, 'success'));
      onClose();
      resetForm();
    } catch (error) {
      dispatch(newNotification(`Failed to add ${id}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <>
      <Button type="submit" colorScheme="teal" variant="ghost">
        Add
      </Button>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Are you sure?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <Text>
              Add <Text as="b">{id}</Text> as {role === 'owner' ? 'an' : 'a'} {role} of {projectName}?
            </Text>
          </ModalBody>
          <ModalFooter>
            <HStack spacing={3}>
              <Button colorScheme="teal" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button colorScheme="teal" onClick={handleAddNewToken} isLoading={isLoading} loadingText="Adding">
                Add
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
