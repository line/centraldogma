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
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import { usePushFileChangesMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { useAppDispatch } from 'dogma/store';
import Router from 'next/router';
import { useForm } from 'react-hook-form';

type FormData = {
  summary: string;
  detail: string;
};

export const DeleteFileModal = ({
  isOpen,
  onClose,
  path,
  projectName,
  repoName,
}: {
  isOpen: boolean;
  onClose: () => void;
  path: string;
  projectName: string;
  repoName: string;
}) => {
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
      dispatch(
        createMessage({
          title: 'File deleted',
          text: `Successfully deleted ${path}`,
          type: 'success',
        }),
      );
      reset();
      Router.push(`/app/projects/${projectName}/repos/${repoName}/list/head/`);
    } catch (error) {
      dispatch(
        createMessage({
          title: `Failed to delete ${path}`,
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
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
