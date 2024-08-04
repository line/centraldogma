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
import { useAddNewProjectMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';
import Router from 'next/router';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { IoMdArrowDropdown } from 'react-icons/io';

const ENTITY_NAME_PATTERN = /^[0-9A-Za-z](?:[-+_0-9A-Za-z.]*[0-9A-Za-z])?$/;

type FormData = {
  name: string;
};

export const NewProject = () => {
  const [addNewProject, { isLoading }] = useAddNewProjectMutation();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();
  const onSubmit = async (data: FormData) => {
    const response = await addNewProject(data);
    if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
      dispatch(
        newNotification(
          'Failed to create a new project',
          ErrorMessageParser.parse((response as { error: FetchBaseQueryError | SerializedError }).error),
          'error',
        ),
      );
      return;
    }
    Router.push(`/app/projects/${data.name}/`);
    reset();
    onClose();
    dispatch(newNotification('New project created', `Successfully created ${data.name}`, 'success'));
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" mr={4} onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Project
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="max-content">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Create a new project
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="max-content">
            <FormControl isInvalid={errors.name ? true : false} isRequired>
              <FormLabel>Project name</FormLabel>
              <Input
                type="text"
                placeholder="my-project-name"
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
