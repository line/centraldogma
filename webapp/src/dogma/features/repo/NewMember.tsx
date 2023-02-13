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
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  useDisclosure,
} from '@chakra-ui/react';
import { useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { ConfirmAddMember } from './ConfirmAddMember';
import { useState } from 'react';

type FormData = {
  id: string;
  role: string;
};

export const NewMember = ({ projectName }: { projectName: string }) => {
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
  const [id, setId] = useState('');
  const [role, setRole] = useState('member');
  const onSubmit = async (data: FormData) => {
    setId(data.id);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          New Member
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
            <FormControl isInvalid={errors.id ? true : false} isRequired>
              <FormLabel>Login ID</FormLabel>
              <Input type="text" placeholder="abc123" {...register('id', { required: true })} />
              {errors.id && <FormErrorMessage>ID is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup defaultValue="member" mt={3} onChange={setRole} value={role}>
              <Stack spacing={5} direction="row">
                <Radio colorScheme="teal" key="member" value="member">
                  Member
                </Radio>
                <Radio colorScheme="teal" key="owner" value="owner">
                  Owner
                </Radio>
              </Stack>
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <ConfirmAddMember
              projectName={projectName}
              id={id}
              role={role}
              isOpen={isConfirmAddOpen}
              onClose={onConfirmAddClose}
              resetForm={reset}
            />
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
