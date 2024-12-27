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
import { AddUserOrTokenRepositoryRoleDto } from 'dogma/features/repo/settings/AddUserOrTokenRepositoryRoleDto';
import { UserOrTokenRepositoryRoleDto } from 'dogma/features/repo/RepositoriesMetadataDto';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { ApiAction } from 'dogma/features/api/apiSlice';
import { AppMemberDetailDto } from 'dogma/features/project/settings/members/AppMemberDto';

interface MemberOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  loginId: string;
  role: string;
};

export const NewUserRepositoryRole = ({
  projectName,
  repoName,
  members,
  addUserRepositoryRole,
  isLoading,
  userRepositoryRole,
}: {
  projectName: string;
  repoName: string;
  members: AppMemberDetailDto[];
  addUserRepositoryRole: ApiAction<AddUserOrTokenRepositoryRoleDto, void>;
  isLoading: boolean;
  userRepositoryRole: UserOrTokenRepositoryRoleDto;
}) => {
  const memberOptions: MemberOptionType[] = members
    .filter((member) => !(member.login in userRepositoryRole))
    .map((member) => ({
      value: member.login,
      label: member.login,
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
  const [loginId, setLoginId] = useState('');
  const [role, setRole] = useState('read');
  const onSubmit = async (data: FormData) => {
    setLoginId(data.loginId);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New User Role
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
            <FormControl isInvalid={errors.loginId ? true : false} isRequired>
              {memberOptions.length ? (
                <Controller
                  control={control}
                  name="loginId"
                  rules={{ required: true }}
                  render={({ field: { onChange, value, name, ref } }) => (
                    <Select
                      ref={ref}
                      id="loginId"
                      name={name}
                      options={memberOptions}
                      // The default value of React Select must be null (and not undefined)
                      value={memberOptions.find((option) => option.value === value) || null}
                      onChange={(option) => option && onChange(option.value)}
                      placeholder="Enter Login ID ..."
                      closeMenuOnSelect={true}
                      openMenuOnFocus={true}
                      isSearchable={true}
                      isClearable={true}
                    />
                  )}
                />
              ) : (
                <FormHelperText>No members available</FormHelperText>
              )}
              {errors.loginId && <FormErrorMessage>Login ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup defaultValue="none" mt={3} colorScheme="teal" onChange={setRole} value={role}>
              {memberOptions.length ? (
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
            {memberOptions.length ? (
              <ConfirmAddUserOrTokenRepositoryRole
                projectName={projectName}
                repoName={repoName}
                entityType={'user'}
                loginId={loginId}
                repositoryRole={role}
                isOpen={isConfirmAddOpen}
                onClose={onConfirmAddClose}
                resetForm={reset}
                addUserRepositoryRole={addUserRepositoryRole}
                isLoading={isLoading}
              />
            ) : (
              <ChakraLink href={`/app/projects/${projectName}/metadata/#members`} color="teal">
                Go to project {projectName}&apos;s member page
              </ChakraLink>
            )}
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
