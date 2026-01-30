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
  Center,
  Divider,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  HStack,
  Input,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  Tag,
  Textarea,
  VStack,
} from '@chakra-ui/react';
import { HiOutlineIdentification } from 'react-icons/hi';

import { GoRepo } from 'react-icons/go';
import { useForm } from 'react-hook-form';
import React, { useState } from 'react';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import FieldErrorMessage from 'dogma/common/components/form/FieldErrorMessage';
import { VariableDto, VariableType } from 'dogma/features/project/settings/variables/VariableDto';
import { FiBox, FiCodesandbox, FiInfo } from 'react-icons/fi';
import { CiText } from 'react-icons/ci';
import { TbJson } from 'react-icons/tb';

interface VariableFormProps {
  projectName: string;
  repoName?: string;
  defaultValue: VariableDto;
  onSubmit: (variable: VariableDto, onSuccess: () => void) => Promise<void>;
  isWaitingResponse: boolean;
}

interface VariableTypeWithDescription {
  type: VariableType;
  description: string;
}

const VARIABLE_TYPES: VariableTypeWithDescription[] = [
  { type: 'STRING', description: 'A string value' },
  { type: 'JSON', description: 'A JSON value' },
];

const validateJsonValue = (value: string) => {
  try {
    JSON.parse(value);
    return true;
  } catch {
    return 'Value must be valid JSON';
  }
};

const VariableForm = ({
  projectName,
  repoName,
  defaultValue,
  onSubmit,
  isWaitingResponse,
}: VariableFormProps) => {
  const [variableType, setVariableType] = useState<string>(defaultValue.type);

  const isNew = defaultValue.id === '';
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<VariableDto>({
    defaultValues: {},
  });

  return (
    <form onSubmit={handleSubmit((variable) => onSubmit(variable, reset))}>
      <Center>
        <VStack width="80%" align="left">
          <Heading size="lg" mb={4}>
            {isNew ? 'New Variable' : 'Edit Variable'}
          </Heading>
          {repoName ? (
            <HStack paddingBottom={2}>
              <LabelledIcon icon={GoRepo} text="Repository" />
              <Tag fontWeight={'bold'}>{repoName}</Tag>
            </HStack>
          ) : (
            <HStack paddingBottom={2}>
              <LabelledIcon icon={FiBox} text="Project" />
              <Tag fontWeight={'bold'}>{projectName}</Tag>
            </HStack>
          )}
          <Divider />
          <FormControl isRequired isInvalid={errors.id != null}>
            <FormLabel>
              <LabelledIcon icon={HiOutlineIdentification} text="Variable ID" />
            </FormLabel>
            <Input
              id="id"
              name="id"
              type="text"
              readOnly={!isNew}
              placeholder="The variable ID"
              defaultValue={defaultValue.id}
              {...register('id', { required: true, pattern: /^[a-zA-Z][a-zA-Z0-9]*$/ })}
            />
            {errors.id ? (
              <FieldErrorMessage error={errors.id} fieldName="variable ID" />
            ) : (
              <FormHelperText>
                The variable ID is used to reference the variable in your templates. It must be unique and
                contain only alphanumeric characters, starting with a letter.
              </FormHelperText>
            )}
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.type != null}>
            <FormLabel>
              <LabelledIcon icon={FiCodesandbox} text="Type" />
            </FormLabel>
            <RadioGroup onChange={setVariableType} value={variableType}>
              <Stack direction="row">
                {VARIABLE_TYPES.map((variableType) => (
                  <Radio
                    key={variableType.type}
                    {...register('type')}
                    value={variableType.type}
                    marginRight={2}
                    defaultChecked={variableType.type === defaultValue.type}
                  >
                    {variableType.description}
                  </Radio>
                ))}
              </Stack>
            </RadioGroup>
            <FieldErrorMessage error={errors.type} fieldName="variable type" />
            <FormHelperText>
              A string value renders directly inside your templates. A JSON value must be valid JSON so your
              templates can access their keys safely.
            </FormHelperText>
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.value != null}>
            <FormLabel>
              <LabelledIcon icon={variableType === 'STRING' ? CiText : TbJson} text="Value" />
            </FormLabel>
            {variableType === 'STRING' ? (
              <Input
                id="value-string"
                name="value"
                placeholder="A string value"
                defaultValue={defaultValue.value}
                {...register('value', { required: true, validate: () => true })}
              />
            ) : (
              <Textarea
                id="value-json"
                height="xs"
                fontFamily="monospace"
                name="value"
                defaultValue={defaultValue.value}
                placeholder={`{ 
    "json": "value" 
}`}
                {...register('value', {
                  required: true,
                  validate: validateJsonValue,
                })}
              />
            )}
            <FieldErrorMessage error={errors.value} />
          </FormControl>
          <Spacer />

          <FormControl isInvalid={errors.description != null}>
            <FormLabel>
              <LabelledIcon icon={FiInfo} text="Description" />
            </FormLabel>
            <Textarea
              id="description"
              height="xxs"
              fontFamily="monospace"
              name="description"
              defaultValue={defaultValue.description}
              placeholder={'A description for the variable'}
              {...register('description')}
            />
            <FieldErrorMessage error={errors.description} />
            <FormHelperText>
              The description helps you and your team understand the purpose of this variable.
            </FormHelperText>
          </FormControl>
          <Spacer />
          <Spacer />
          {isNew ? (
            <Button type="submit" colorScheme="blue" loadingText="Creating">
              Create a new variable
            </Button>
          ) : (
            <Button type="submit" colorScheme="green" isLoading={isWaitingResponse} loadingText="Updating">
              Update the variable
            </Button>
          )}
        </VStack>
      </Center>
    </form>
  );
};

export default VariableForm;
