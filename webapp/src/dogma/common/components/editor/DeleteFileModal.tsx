import {
  Button,
  FormControl,
  FormErrorMessage,
  Input,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  Textarea,
} from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { useForm } from 'react-hook-form';

type FormData = {
  summary: string;
  detail: string;
};

type DeleteFileModalProps = {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  path: string;
  projectName: string;
  repoName: string;
};

export const DeleteFileModal = ({
  isOpen,
  onClose,
  onSuccess,
  path,
  projectName,
  repoName,
}: DeleteFileModalProps) => {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const [deleteFile, { isLoading }] = usePushFileChangesMutation();
  const dispatch = useAppDispatch();
  const onSubmit = async (formData: FormData) => {
    const data = {
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
      },
      changes: [
        {
          path: path,
          type: 'REMOVE',
          content: '',
        },
      ],
    };
    try {
      const response = await deleteFile({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      dispatch(newNotification('File deleted', `Successfully deleted ${path}`, 'success'));
      reset();
      onSuccess();
    } catch (error) {
      dispatch(newNotification(`Failed to delete ${path}`, ErrorMessageParser.parse(error), 'error'));
    }
  };
  return (
    <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
      <ModalOverlay />
      <form onSubmit={handleSubmit(onSubmit)}>
        <ModalContent>
          <ModalHeader>Delete {path}?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <FormControl isRequired>
              <Input
                id="summary"
                name="summary"
                type="text"
                placeholder="Add a summary"
                {...register('summary', { required: true })}
                mb={4}
              />
              {errors.summary && <FormErrorMessage>Please enter the commit message</FormErrorMessage>}
            </FormControl>
            <Textarea
              id="description"
              name="description"
              placeholder="Add an optional extended description..."
              {...register('detail')}
            />
          </ModalBody>
          <ModalFooter>
            <Button type="submit" colorScheme="red" mr={3} isLoading={isLoading} loadingText="Deleting">
              Delete
            </Button>
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
          </ModalFooter>
        </ModalContent>
      </form>
    </Modal>
  );
};
