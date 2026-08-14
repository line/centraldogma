import {
  Box,
  Button,
  Checkbox,
  FormControl,
  FormErrorMessage,
  FormHelperText,
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
  Tooltip,
  useDisclosure,
} from '@chakra-ui/react';
import { useAddNewRepoMutation, useGetMetadataByProjectNameQuery } from 'dogma/features/api/apiSlice';
import { allowsPublicRepositories } from 'dogma/features/project/ProjectMetadataDto';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import { useAppDispatch } from 'dogma/hooks';
import Router from 'next/router';
import { useForm } from 'react-hook-form';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { IoMdArrowDropdown } from 'react-icons/io';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';

const ENTITY_NAME_PATTERN = /^[0-9A-Za-z](?:[-+_0-9A-Za-z.]*[0-9A-Za-z])?$/;

type FormData = {
  name: string;
  isPublic: boolean;
};

export const NewRepo = ({ projectName }: { projectName: string }) => {
  const [addNewRepo, { isLoading }] = useAddNewRepoMutation();
  const { data: metadata, isLoading: isMetadataLoading } = useGetMetadataByProjectNameQuery(projectName, {
    skip: projectName === 'dogma',
  });
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const dispatch = useAppDispatch();

  if (projectName === 'dogma') {
    return null;
  }
  const publicAllowed = allowsPublicRepositories(metadata);

  const onSubmit = async (data: FormData) => {
    try {
      const response = await addNewRepo({ projectName, data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      Router.push(`/app/projects/${projectName}/repos/${data.name}/tree/head/`);
      reset();
      onClose();
      dispatch(newNotification('New repository created', `Successfully created ${data.name}`, 'success'));
    } catch (error) {
      dispatch(newNotification('Failed to create a new repository', ErrorMessageParser.parse(error), 'error'));
    }
  };

  return (
    <WithProjectRole projectName={projectName} roles={['OWNER', 'MEMBER']}>
      {() => (
        <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
          <PopoverTrigger>
            <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
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
                <FormControl mt={4}>
                  <Tooltip
                    label="The project settings do not allow public repositories."
                    isDisabled={publicAllowed}
                  >
                    <Box display="inline-block">
                      <Checkbox
                        colorScheme="teal"
                        isDisabled={!publicAllowed || isMetadataLoading}
                        {...register('isPublic')}
                      >
                        Make this repository public
                      </Checkbox>
                    </Box>
                  </Tooltip>
                  <FormHelperText pl={1}>
                    Readable by everyone who can sign in, including application tokens and certificates with
                    guest access allowed.
                  </FormHelperText>
                </FormControl>
              </PopoverBody>
              <PopoverFooter
                border="0"
                display="flex"
                alignItems="center"
                justifyContent="space-between"
                pb={4}
              >
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
      )}
    </WithProjectRole>
  );
};
