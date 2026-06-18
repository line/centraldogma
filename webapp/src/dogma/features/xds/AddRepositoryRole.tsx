/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
import {
  Button,
  FormControl,
  FormErrorMessage,
  FormHelperText,
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
import { Controller, useForm } from 'react-hook-form';
import { IoMdArrowDropdown } from 'react-icons/io';
import { useState } from 'react';
import { OptionBase, Select } from 'chakra-react-select';
import { RepositoryRole } from 'dogma/features/xds/MetadataDto';

type FormData = {
  id: string;
};

interface IdOption extends OptionBase {
  value: string;
  label: string;
}

export const AddRepositoryRole = ({
  label,
  placeholder,
  isLoading,
  onAdd,
  options,
}: {
  label: string;
  placeholder: string;
  isLoading: boolean;
  onAdd: (id: string, role: RepositoryRole) => Promise<boolean>;
  // When provided, the id is chosen from this dropdown (e.g. existing app identities) instead of typed.
  options?: IdOption[];
}) => {
  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>();
  const { isOpen, onToggle, onClose } = useDisclosure();
  const [role, setRole] = useState<RepositoryRole>('READ');

  // When an options list is provided but empty, there is no valid id to assign, so submission is blocked.
  const noSelectableOptions = options != null && options.length === 0;

  const onSubmit = async (data: FormData) => {
    // Guard against a stale/empty id (e.g. a cleared select, or empty options) that form validation alone
    // would not catch.
    if (!data.id || noSelectableOptions) {
      return;
    }
    const ok = await onAdd(data.id, role);
    if (ok) {
      reset();
      setRole('READ');
      onClose();
    }
  };

  return (
    <Popover placement="bottom" isOpen={isOpen} onClose={onClose}>
      <PopoverTrigger>
        <Button colorScheme="teal" size="sm" onClick={onToggle} rightIcon={<IoMdArrowDropdown />}>
          {label}
        </Button>
      </PopoverTrigger>
      <PopoverContent minWidth="md">
        <PopoverHeader pt={4} fontWeight="bold" border={0} mb={3}>
          {label}
        </PopoverHeader>
        <PopoverArrow />
        <PopoverCloseButton />
        <form onSubmit={handleSubmit(onSubmit)}>
          <PopoverBody minWidth="md">
            <FormControl isInvalid={errors.id ? true : false} isRequired>
              {options ? (
                options.length ? (
                  <Controller
                    control={control}
                    name="id"
                    rules={{ required: true }}
                    render={({ field: { onChange, value, name, ref } }) => (
                      <Select<IdOption>
                        ref={ref}
                        name={name}
                        options={options}
                        // react-select requires null (not undefined) for an empty value.
                        value={options.find((option) => option.value === value) || null}
                        // Clear the form value when the select is cleared so a stale id is not submitted.
                        onChange={(option) => onChange(option ? option.value : '')}
                        placeholder={placeholder}
                        closeMenuOnSelect
                        openMenuOnFocus
                        isSearchable
                        isClearable
                      />
                    )}
                  />
                ) : (
                  <FormHelperText>No application identities available. Create one first.</FormHelperText>
                )
              ) : (
                <Input id="id" placeholder={placeholder} {...register('id', { required: true })} />
              )}
              {errors.id && <FormErrorMessage>This field is required</FormErrorMessage>}
            </FormControl>
            <RadioGroup mt={3} colorScheme="teal" onChange={(v) => setRole(v as RepositoryRole)} value={role}>
              <Stack spacing={5} direction="row">
                <Radio value="READ">Read</Radio>
                <Radio value="WRITE">Write</Radio>
                <Radio value="ADMIN">Admin</Radio>
              </Stack>
            </RadioGroup>
          </PopoverBody>
          <PopoverFooter border="0" display="flex" alignItems="center" justifyContent="space-between" pb={4}>
            <Spacer />
            <Button
              type="submit"
              colorScheme="teal"
              isLoading={isLoading}
              loadingText="Adding"
              isDisabled={noSelectableOptions}
            >
              Add
            </Button>
          </PopoverFooter>
        </form>
      </PopoverContent>
    </Popover>
  );
};
