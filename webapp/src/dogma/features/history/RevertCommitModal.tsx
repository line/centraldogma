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
import { useRevertRepositoryMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useAppDispatch } from 'dogma/hooks';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';

type FormData = {
  summary: string;
  detail: string;
};

type RevertCommitModalProps = {
  isOpen: boolean;
  onClose: () => void;
  projectName: string;
  repoName: string;
  headRevision: number;
  targetRevision: number;
};

export const RevertCommitModal = ({
  isOpen,
  onClose,
  projectName,
  repoName,
  headRevision,
  targetRevision,
}: RevertCommitModalProps) => {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const [revertRepository, { isLoading }] = useRevertRepositoryMutation();
  const dispatch = useAppDispatch();

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    reset({
      summary: `Revert to r${targetRevision}`,
      detail: `Rollback repository from r${headRevision} to r${targetRevision}.`,
    });
  }, [isOpen, targetRevision, headRevision, reset]);

  const onSubmit = async (formData: FormData) => {
    const data = {
      targetRevision,
      commitMessage: {
        summary: formData.summary,
        detail: formData.detail,
      },
    };
    try {
      const response = await revertRepository({ projectName, repoName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      if (response === null) {
        dispatch(
          newNotification('No changes to revert', `Repository is already at r${targetRevision}`, 'info'),
        );
      } else {
        dispatch(newNotification('Repository reverted', `Reverted to r${targetRevision}`, 'success'));
      }
      reset();
      onClose();
    } catch (error) {
      dispatch(newNotification(`Failed to revert`, ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} isCentered motionPreset="slideInBottom">
      <ModalOverlay />
      <form onSubmit={handleSubmit(onSubmit)}>
        <ModalContent>
          <ModalHeader>Revert to r{targetRevision}?</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            <FormControl isRequired isInvalid={!!errors.summary}>
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
            <Button type="submit" colorScheme="red" mr={3} isLoading={isLoading} loadingText="Reverting">
              Revert
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
