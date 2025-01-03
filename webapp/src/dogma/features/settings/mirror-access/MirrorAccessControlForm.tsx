/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
  Center,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  Input,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  VStack,
} from '@chakra-ui/react';
import { HiOutlineIdentification } from 'react-icons/hi';
import { Controller, useForm } from 'react-hook-form';
import React, { useMemo } from 'react';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import FieldErrorMessage from 'dogma/common/components/form/FieldErrorMessage';
import { MirrorAccessControlRequest } from 'dogma/features/settings/mirror-access/MirrorAccessControl';
import { LuRegex } from 'react-icons/lu';
import { MdOutlineDescription, MdPolicy } from 'react-icons/md';
import { RiSortNumberAsc } from 'react-icons/ri';

interface MirrorAccessControlFormProps {
  defaultValue: MirrorAccessControlRequest;
  onSubmit: (credential: MirrorAccessControlRequest, onSuccess: () => void) => Promise<void>;
  isWaitingResponse: boolean;
}

const MirrorAccessControlForm = ({
  defaultValue,
  onSubmit,
  isWaitingResponse,
}: MirrorAccessControlFormProps) => {
  const isNew = defaultValue.id === '';
  const {
    register,
    handleSubmit,
    setValue,
    control,
    formState: { errors, isDirty },
  } = useForm<MirrorAccessControlRequest>({
    defaultValues: {},
  });

  useMemo(() => {
    if (!isNew) {
      // @ts-expect-error 'allow' in a radio group is a string
      setValue('allow', defaultValue.allow + '');
    }
  }, [isNew, setValue, defaultValue.allow]);

  return (
    <form onSubmit={handleSubmit((data) => onSubmit(data, () => {}))}>
      <Center>
        <VStack width="80%" align="left">
          <Heading size="lg" mb={4}>
            {isNew ? 'New Mirror Access Control' : 'Edit Mirror Access Control'}
          </Heading>
          <FormControl isRequired isInvalid={errors.id != null}>
            <FormLabel>
              <LabelledIcon icon={HiOutlineIdentification} text="ID" />
            </FormLabel>
            <Input
              id="id"
              name="id"
              type="text"
              readOnly={!isNew}
              placeholder="ID"
              defaultValue={defaultValue.id}
              {...register('id', { required: true, pattern: /^[a-zA-Z0-9-_.]+$/ })}
            />
            {errors.id ? (
              <FieldErrorMessage error={errors.id} fieldName="ID" />
            ) : (
              <FormHelperText>
                The mirror access control ID must be unique and contain alphanumeric characters, dashes,
                underscores, and periods only.
              </FormHelperText>
            )}
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.targetPattern != null}>
            <FormLabel>
              <LabelledIcon icon={LuRegex} text="Git URI pattern" />
            </FormLabel>
            <Input
              id="targetPattern"
              name="targetPattern"
              placeholder=".*://github.com/line/.*"
              defaultValue={defaultValue.targetPattern}
              {...register('targetPattern', { required: true })}
            />
            <FormHelperText>
              The pattern of the mirror URI for access control. Regular expressions are supported.
            </FormHelperText>

            <FieldErrorMessage error={errors.targetPattern} />
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.allow != null}>
            <FormLabel>
              <LabelledIcon icon={MdPolicy} text="Access" />
            </FormLabel>
            <Controller
              name="allow"
              rules={{ required: true }}
              control={control}
              render={({ field: { onChange, value } }) => (
                <RadioGroup onChange={onChange} value={value + ''} defaultValue={defaultValue.allow + ''}>
                  <Stack direction="row">
                    <Radio value="true" marginRight={2}>
                      Allow
                    </Radio>
                    <Radio value="false">Disallow</Radio>
                  </Stack>
                </RadioGroup>
              )}
            />
            <FieldErrorMessage error={errors.allow} fieldName="Access" />
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.order != null}>
            <FormLabel>
              <LabelledIcon icon={RiSortNumberAsc} text="Order" />
            </FormLabel>
            <Input
              id="order"
              type={'number'}
              name="order"
              placeholder="0"
              defaultValue={defaultValue.order}
              {...register('order', { required: true })}
            />
            {errors.order ? (
              <FieldErrorMessage error={errors.order} />
            ) : (
              <FormHelperText>
                The order of the mirror access control. Lower numbers are evaluated first.
              </FormHelperText>
            )}
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.description != null}>
            <FormLabel>
              <LabelledIcon icon={MdOutlineDescription} text="Description" />
            </FormLabel>
            <Input
              id="description"
              name="description"
              placeholder="Example: Allow access to the repository in the LINE organization"
              defaultValue={defaultValue.description}
              {...register('description', { required: true })}
            />
            <FieldErrorMessage error={errors.description} />
          </FormControl>
          <Spacer />

          {isNew ? (
            <Button type="submit" colorScheme="blue" loadingText="Creating" isLoading={isWaitingResponse}>
              Create a new mirror access control
            </Button>
          ) : (
            <Button
              type="submit"
              colorScheme="green"
              isDisabled={!isDirty}
              isLoading={isWaitingResponse}
              loadingText="Updating"
            >
              Update the mirror access control
            </Button>
          )}
        </VStack>
      </Center>
    </form>
  );
};

export default MirrorAccessControlForm;
