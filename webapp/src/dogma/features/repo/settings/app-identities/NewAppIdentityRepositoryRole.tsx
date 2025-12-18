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
import { ConfirmAddUserOrAppIdentityRepositoryRole } from 'dogma/features/repo/settings/ConfirmAddUserOrAppIdentityRepositoryRole';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { UserOrAppIdentityRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { AddUserOrAppIdentityRepositoryRoleDto } from 'dogma/features/repo/settings/AddUserOrAppIdentityRepositoryRoleDto';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { AppIdDetailDto } from 'dogma/features/project/settings/app-identities/AppIdDto';

interface AppIdentityOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  appId: string;
  role: string;
};

export const NewAppIdentityRepositoryRole = ({
  projectName,
  repoName,
  appIds,
  addAppIdentityRepositoryRole,
  isLoading,
  appIdentityRepositoryRole,
}: {
  projectName: string;
  repoName: string;
  appIds: AppIdDetailDto[];
  addAppIdentityRepositoryRole: ApiAction<AddUserOrAppIdentityRepositoryRoleDto, void>;
  isLoading: boolean;
  appIdentityRepositoryRole: UserOrAppIdentityRepositoryRoleDto;
}) => {
  const appIdentityOptions: AppIdentityOptionType[] = appIds
    .filter((appIdentity) => !(appIdentity.appId in appIdentityRepositoryRole))
    .map((appIdentity) => ({
      value: appIdentity.appId,
      label: appIdentity.appId,
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
          New App Identity Role
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
              {appIdentityOptions.length ? (
                <Controller
                  control={control}
                  name="appId"
                  rules={{ required: true }}
                  render={({ field: { onChange, value, name, ref } }) => (
                    <Select
                      ref={ref}
                      id="appId"
                      name={name}
                      options={appIdentityOptions}
                      // The default value of React Select must be null (and not undefined)
                      value={appIdentityOptions.find((option) => option.value === value) || null}
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
                <FormHelperText>No app identities available</FormHelperText>
              )}
              {errors.appId && <FormErrorMessage>App ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup defaultValue="none" mt={3} colorScheme="teal" onChange={setRole} value={role}>
              {appIdentityOptions.length ? (
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
            {appIdentityOptions.length ? (
              <ConfirmAddUserOrAppIdentityRepositoryRole
                projectName={projectName}
                repoName={repoName}
                entityType={'appIdentity'}
                loginId={appId}
                repositoryRole={role}
                isOpen={isConfirmAddOpen}
                onClose={onConfirmAddClose}
                resetForm={reset}
                addUserRepositoryRole={addAppIdentityRepositoryRole}
                isLoading={isLoading}
              />
            ) : (
              <ChakraLink href={`/app/projects/${projectName}/settings/tokens`} color="teal">
                Go to project {projectName}&apos;s app identity page
              </ChakraLink>
            )}
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
