import {
  Button,
  FormControl,
  FormErrorMessage,
  FormHelperText,
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
import { Controller, useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { useState } from 'react';
import { OptionBase, Select } from 'chakra-react-select';
import { ConfirmAddUserPermission } from 'dogma/features/repo/permissions/ConfirmAddUserPermission';
import { AppTokenDetailDto } from '../../metadata/AppTokenDto';
import { AddUserPermissionDto } from 'dogma/features/repo/permissions/AddUserPermissionDto';
import {
  MutationDefinition,
  BaseQueryFn,
  FetchArgs,
  FetchBaseQueryError,
  FetchBaseQueryMeta,
} from '@reduxjs/toolkit/dist/query';
import { MutationTrigger } from '@reduxjs/toolkit/dist/query/react/buildHooks';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { PerUserPermissionDto } from 'dogma/features/repo/RepoPermissionDto';

interface TokenOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  appId: string;
  permission: string;
};

export const NewRepoTokenPermission = ({
  projectName,
  repoName,
  tokens,
  addTokenPermission,
  isLoading,
  perUserPermissions,
}: {
  projectName: string;
  repoName: string;
  tokens: AppTokenDetailDto[];
  addTokenPermission: MutationTrigger<
    MutationDefinition<
      AddUserPermissionDto,
      BaseQueryFn<
        string | FetchArgs,
        unknown,
        FetchBaseQueryError,
        Record<string, unknown>,
        FetchBaseQueryMeta
      >,
      'Metadata',
      void,
      'api'
    >
  >;
  isLoading: boolean;
  perUserPermissions: PerUserPermissionDto;
}) => {
  const tokenOptions: TokenOptionType[] = tokens
    .filter((token) => !(token.appId in perUserPermissions))
    .map((token) => ({
      value: token.appId,
      label: token.appId,
    }));
  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const {
    isOpen: isConfirmAddOpen,
    onToggle: onConfirmAddToggle,
    onClose: onConfirmAddClose,
  } = useDisclosure();
  const [appId, setAppId] = useState('');
  const [permission, setPermission] = useState('read');
  const onSubmit = async (data: FormData) => {
    setAppId(data.appId);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Token Permission
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="md">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Repository {repoName}
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="max-content">
            <FormControl isInvalid={errors.appId ? true : false} isRequired>
              {tokenOptions.length ? (
                <Controller
                  control={control}
                  name="appId"
                  rules={{ required: true }}
                  render={({ field: { onChange, value, name, ref } }) => (
                    <Select
                      ref={ref}
                      id="appId"
                      name={name}
                      options={tokenOptions}
                      // The default value of React Select must be null (and not undefined)
                      value={tokenOptions.find((option) => option.value === value) || null}
                      onChange={(option) => option && onChange(option.value)}
                      placeholder="Enter App ID ..."
                      closeMenuOnSelect={true}
                      openMenuOnFocus={true}
                      isSearchable={true}
                      isClearable={true}
                    />
                  )}
                />
              ) : (
                <FormHelperText>No tokens available</FormHelperText>
              )}
              {errors.appId && <FormErrorMessage>App ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup
              defaultValue="none"
              mt={3}
              colorScheme="teal"
              onChange={setPermission}
              value={permission}
            >
              {tokenOptions.length ? (
                <Stack spacing={5} direction="row">
                  <Radio value="read">Read Only</Radio>
                  <Radio value="write">Read Write</Radio>
                </Stack>
              ) : (
                ''
              )}
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            {tokenOptions.length ? (
              <ConfirmAddUserPermission
                projectName={projectName}
                repoName={repoName}
                loginId={appId}
                permission={permission}
                isOpen={isConfirmAddOpen}
                onClose={onConfirmAddClose}
                resetForm={reset}
                addUserPermission={addTokenPermission}
                isLoading={isLoading}
              />
            ) : (
              <ChakraLink href={`/app/projects/${projectName}/metadata/#tokens`} color="teal">
                Go to project {projectName}&apos;s token page
              </ChakraLink>
            )}
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
