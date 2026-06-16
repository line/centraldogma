import {
  Button,
  FormControl,
  FormErrorMessage,
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
import { useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { useState } from 'react';
import { ConfirmAddUserOrAppIdentityRepositoryRole } from 'dogma/features/repo/settings/ConfirmAddUserOrAppIdentityRepositoryRole';
import { AddUserOrAppIdentityRepositoryRoleDto } from 'dogma/features/repo/settings/AddUserOrAppIdentityRepositoryRoleDto';
import { ApiAction } from 'dogma/features/api/apiSlice';

type FormData = {
  loginId: string;
  role: string;
};

export const NewUserRepositoryRole = ({
  projectName,
  repoName,
  addUserRepositoryRole,
  isLoading,
}: {
  projectName: string;
  repoName: string;
  addUserRepositoryRole: ApiAction<AddUserOrAppIdentityRepositoryRoleDto, void>;
  isLoading: boolean;
}) => {
  const {
    register,
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
              <Input
                id="loginId"
                placeholder="Enter Login ID ..."
                {...register('loginId', { required: true })}
              />
              {errors.loginId && <FormErrorMessage>Login ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup defaultValue="none" mt={3} colorScheme="teal" onChange={setRole} value={role}>
              <Stack spacing={5} direction="row">
                <Radio value="ADMIN">Admin</Radio>
                <Radio value="WRITE">Write</Radio>
                <Radio value="READ">Read</Radio>
              </Stack>
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <ConfirmAddUserOrAppIdentityRepositoryRole
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
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
