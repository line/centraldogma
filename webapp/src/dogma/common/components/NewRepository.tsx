import {
  Button,
  FormControl,
  FormErrorMessage,
  FormLabel,
  Input,
  Popover,
  PopoverArrow,
  PopoverBody,
  PopoverCloseButton,
  PopoverContent,
  PopoverFooter,
  PopoverHeader,
  PopoverTrigger,
  Spacer,
  useDisclosure,
} from '@chakra-ui/react';
import { useAddNewRepoMutation } from 'dogma/features/api/apiSlice';
import { createMessage } from 'dogma/features/message/messageSlice';
import { useAppDispatch } from 'dogma/store';
import Router from 'next/router';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/dist/query';
import ErrorHandler from 'dogma/features/services/ErrorHandler';
import { IoMdArrowDropdown } from 'react-icons/io';

const ENTITY_NAME_PATTERN = /^[0-9A-Za-z](?:[-+_0-9A-Za-z.]*[0-9A-Za-z])?$/;

type FormData = {
  name: string;
};

export const NewRepository = ({ projectName }: { projectName: string }) => {
  const [addNewRepo, { isLoading }] = useAddNewRepoMutation();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const onSubmit = async (data: FormData) => {
    try {
      const response = await addNewRepo({ projectName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      Router.push(`/app/projects/${projectName}/repos/${data.name}/list/head/`);
      reset();
      onClose();
      dispatch(
        createMessage({
          title: 'New repository created',
          text: `Successfully created ${data.name}`,
          type: 'success',
        }),
      );
    } catch (error) {
      dispatch(
        createMessage({
          title: 'Failed to create a new repository',
          text: ErrorHandler.handle(error),
          type: 'error',
        }),
      );
    }
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Repository
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="max-content">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Project {projectName}
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="max-content">
            <FormControl isInvalid={errors.name ? true : false} isRequired>
              <FormLabel>Repository name</FormLabel>
              <Input
                type="text"
                placeholder="my-repo-name"
                {...register('name', { pattern: ENTITY_NAME_PATTERN })}
              />
              {errors.name && (
                <FormErrorMessage>The first/last character must be alphanumeric</FormErrorMessage>
              )}
            </FormControl>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <Button
              type="submit"
              colorScheme="teal"
              variant="ghost"
              isLoading={isLoading}
              loadingText="Creating"
            >
              Create
            </Button>
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
