import {
  Button,
  Checkbox,
  Flex,
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
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  useDisclosure,
} from '@chakra-ui/react';
import { SerializedError } from '@reduxjs/toolkit';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { DisplaySecretModal } from 'dogma/features/app-identity/DisplaySecretModal';
import { useAddNewAppIdentityMutation } from 'dogma/features/api/apiSlice';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';

const APP_ID_PATTERN = /^[0-9A-Za-z](?:[-+_0-9A-Za-z\.]*[0-9A-Za-z])?$/;

type FormData = {
  appId: string;
  type: 'TOKEN' | 'CERTIFICATE';
  certificateId?: string;
  isSystemAdmin: boolean;
};

export const NewAppIdentity = () => {
  const mtlsEnabled = useAppSelector((state) => state.serverConfig.mtlsEnabled);

  const { isOpen: isNewAppIdentityFormOpen, onToggle: onNewAppIdentityFormToggle, onClose } = useDisclosure();

  const onNewAppIdentityFormClose = () => {
    reset({
      appId: '',
      type: 'TOKEN',
      certificateId: '',
      isSystemAdmin: false,
    });
    onClose();
  };
  const {
    isOpen: isSecretModalOpen,
    onToggle: onSecretModalToggle,
    onClose: onSecretModalClose,
  } = useDisclosure();
  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<FormData>({
    defaultValues: {
      type: 'TOKEN',
    },
  });
  const [addNewAppIdentity, { isLoading }] = useAddNewAppIdentityMutation();
  const [appIdentityDetail, setAppIdentityDetail] = useState(null);
  const dispatch = useAppDispatch();
  const { user } = useAppSelector((state) => state.auth);

  const selectedType = watch('type');

  const onSubmit = async (formData: FormData) => {
    if (formData.type === 'CERTIFICATE' && !formData.certificateId) {
      dispatch(newNotification('Validation Error', 'Certificate ID is required for Certificate type', 'error'));
      return;
    }

    const params = new URLSearchParams();
    params.set('appId', formData.appId);
    params.set('appIdentityType', formData.type);
    params.set('isSystemAdmin', String(formData.isSystemAdmin || false));
    if (formData.type === 'CERTIFICATE' && formData.certificateId) {
      params.set('certificateId', formData.certificateId);
    }
    const data = params.toString();
    try {
      const response = await addNewAppIdentity({ data }).unwrap();
      if ((response as { error: FetchBaseQueryError | SerializedError }).error) {
        throw (response as { error: FetchBaseQueryError | SerializedError }).error;
      }
      setAppIdentityDetail(response);
      onNewAppIdentityFormClose();
      onSecretModalToggle();
    } catch (error) {
      dispatch(
        newNotification('Failed to create a new app identity', ErrorMessageParser.parse(error), 'error'),
      );
    }
  };

  return (
    <>
      <Popover placement="bottom" isOpen={isNewAppIdentityFormOpen} onClose={onNewAppIdentityFormClose}>
        <PopoverTrigger>
          <Button
            size="sm"
            mr={4}
            rightIcon={<IoMdArrowDropdown />}
            colorScheme="teal"
            onClick={onNewAppIdentityFormToggle}
          >
            New App Identity
          </Button>
        </PopoverTrigger>
        <PopoverContent minWidth="max-content">
          <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
            Create a new app identity
          </PopoverHeader>
          <PopoverArrow />
          <PopoverCloseButton />
          <form onSubmit={handleSubmit(onSubmit)}>
            <PopoverBody minWidth="md">
              <FormControl mb={4}>
                <FormLabel>Type</FormLabel>
                <RadioGroup defaultValue="TOKEN">
                  <Stack direction="row" spacing={4}>
                    <Radio value="TOKEN" {...register('type')}>
                      Token
                    </Radio>
                    {mtlsEnabled && (
                      <Radio value="CERTIFICATE" {...register('type')}>
                        Certificate
                      </Radio>
                    )}
                  </Stack>
                </RadioGroup>
              </FormControl>

              <FormControl isInvalid={errors.appId ? true : false} isRequired>
                <FormLabel>Application ID</FormLabel>
                <Input
                  type="text"
                  placeholder="my-app-id"
                  {...register('appId', { pattern: APP_ID_PATTERN })}
                />
                <FormHelperText pl={1}>
                  Register the app identity with a project before accessing it.
                </FormHelperText>
                {errors.appId && (
                  <FormErrorMessage>The first/last character must be alphanumeric</FormErrorMessage>
                )}
              </FormControl>

              {selectedType === 'CERTIFICATE' && (
                <FormControl mt={4} isInvalid={errors.certificateId ? true : false} isRequired>
                  <FormLabel>Certificate ID</FormLabel>
                  <Input
                    type="text"
                    placeholder="certificate-id"
                    {...register('certificateId', {
                      required: selectedType === 'CERTIFICATE',
                    })}
                  />
                  {errors.certificateId && <FormErrorMessage>Certificate ID is required</FormErrorMessage>}
                </FormControl>
              )}

              {user.roles.includes('LEVEL_SYSTEM_ADMIN') && (
                <Flex mt={4}>
                  <Spacer />
                  <Checkbox colorScheme="teal" {...register('isSystemAdmin')}>
                    System Administrator-Level App Identity
                  </Checkbox>
                </Flex>
              )}
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
      <DisplaySecretModal
        isOpen={isSecretModalOpen}
        onClose={onSecretModalClose}
        response={appIdentityDetail}
      />
    </>
  );
};
