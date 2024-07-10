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
import { useGetTokensQuery } from 'dogma/features/api/apiSlice';
import { TokenDto } from 'dogma/features/token/TokenDto';
import { OptionBase, Select } from 'chakra-react-select';
import { ConfirmAddToken } from 'dogma/features/project/settings/tokens/ConfirmAddToken';

interface TokenOptionType extends OptionBase {
  value: string;
  label: string;
}

type FormData = {
  appId: string;
  role: string;
};

export const AddAppToken = ({ projectName }: { projectName: string }) => {
  const result = useGetTokensQuery();
  const tokenOptions: TokenOptionType[] = (result.data || [])
    .filter((token: TokenDto) => !token.deactivation)
    .map((token: TokenDto) => ({
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
  const [role, setRole] = useState('member');
  const onSubmit = async (data: FormData) => {
    setAppId(data.appId);
    onConfirmAddToggle();
  };
  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          Add Token
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="md">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          Add a new token
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="max-content">
            <FormControl isInvalid={errors.appId ? true : false} isRequired>
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
              {errors.appId && <FormErrorMessage>App ID is required</FormErrorMessage>}
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
            <ConfirmAddToken
              projectName={projectName}
              id={appId}
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
