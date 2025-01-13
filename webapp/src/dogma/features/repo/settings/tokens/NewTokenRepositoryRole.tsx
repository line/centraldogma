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
import { ConfirmAddUserOrTokenRepositoryRole } from 'dogma/features/repo/settings/ConfirmAddUserOrTokenRepositoryRole';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { AddUserOrTokenRepositoryRoleDto } from 'dogma/features/repo/settings/AddUserOrTokenRepositoryRoleDto';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { AppTokenDetailDto } from 'dogma/features/project/settings/tokens/AppTokenDto';

interface TokenOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  appId: string;
  role: string;
};

export const NewTokenRepositoryRole = ({
  projectName,
  repoName,
  tokens,
  addTokenRepositoryRole,
  isLoading,
  tokenRepositoryRole,
}: {
  projectName: string;
  repoName: string;
  tokens: AppTokenDetailDto[];
  addTokenRepositoryRole: ApiAction<AddUserOrTokenRepositoryRoleDto, void>;
  isLoading: boolean;
  tokenRepositoryRole: UserOrTokenRepositoryRoleDto;
}) => {
  const tokenOptions: TokenOptionType[] = tokens
    .filter((token) => !(token.appId in tokenRepositoryRole))
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
  const [role, setRole] = useState('read');
  const onSubmit = async (data: FormData) => {
    setAppId(data.appId);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Token Role
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
            <RadioGroup defaultValue="none" mt={3} colorScheme="teal" onChange={setRole} value={role}>
              {tokenOptions.length ? (
                <Stack spacing={5} direction="row">
                  <Radio value="ADMIN">Admin</Radio>
                  <Radio value="WRITE">Write</Radio>
                  <Radio value="READ">Read</Radio>
                </Stack>
              ) : (
                ''
              )}
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            {tokenOptions.length ? (
              <ConfirmAddUserOrTokenRepositoryRole
                projectName={projectName}
                repoName={repoName}
                entityType={'token'}
                loginId={appId}
                repositoryRole={role}
                isOpen={isConfirmAddOpen}
                onClose={onConfirmAddClose}
                resetForm={reset}
                addUserRepositoryRole={addTokenRepositoryRole}
                isLoading={isLoading}
              />
            ) : (
              <ChakraLink href={`/app/projects/${projectName}/settings/tokens`} color="teal">
                Go to project {projectName}&apos;s token page
              </ChakraLink>
            )}
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
