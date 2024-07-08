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
import { useAddNewMemberMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/hooks';

export const ConfirmAddMember = ({
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
  const [addNewMember, { isLoading }] = useAddNewMemberMutation();
  const handleAddNewMember = async () => {
    try {
      const response = await addNewMember({ projectName, id, role }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(
        createMessage({
          title: 'New member saved',
          text: `Successfully added ${id}`,
          type: 'success',
        }),
      );
      onClose();
      resetForm();
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to add ${id}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
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
              <Button
                colorScheme="teal"
                onClick={handleAddNewMember}
                isLoading={isLoading}
                loadingText="Adding"
              >
                Add
              </Button>
            </HStack>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
};
