import {
  Button,
  FormControl,
  FormErrorMessage,
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
import { AppMemberDetailDto } from 'dogma/features/metadata/AppMemberDto';
import { ConfirmAddUserPermission } from 'dogma/features/repo/permissions/ConfirmAddUserPermission';
import {
  MutationDefinition,
  BaseQueryFn,
  FetchArgs,
  FetchBaseQueryError,
  FetchBaseQueryMeta,
} from '@reduxjs/toolkit/dist/query';
import { MutationTrigger } from '@reduxjs/toolkit/dist/query/react/buildHooks';
import { AddUserPermissionDto } from 'dogma/features/repo/permissions/AddUserPermissionDto';

interface MemberOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  loginId: string;
  permission: string;
};

export const NewRepoUserPermission = ({
  projectName,
  repoName,
  members,
  addUserPermission,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  members: AppMemberDetailDto[];
  addUserPermission: MutationTrigger<
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
}) => {
  const memberOptions: MemberOptionType[] = members.map((member: AppMemberDetailDto) => ({
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
  const [permission, setPermission] = useState('none');
  const onSubmit = async (data: FormData) => {
    setLoginId(data.loginId);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New User Permission
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
              {errors.loginId && <FormErrorMessage>Login ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup
              defaultValue="none"
              mt={3}
              colorScheme="teal"
              onChange={setPermission}
              value={permission}
            >
              <Stack spacing={5} direction="row">
                <Radio value="read">Read Only</Radio>
                <Radio value="write">Read Write</Radio>
              </Stack>
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <ConfirmAddUserPermission
              projectName={projectName}
              repoName={repoName}
              loginId={loginId}
              permission={permission}
              isOpen={isConfirmAddOpen}
              onClose={onConfirmAddClose}
              resetForm={reset}
              addUserPermission={addUserPermission}
              isLoading={isLoading}
            />
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
