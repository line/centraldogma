/*
 * Copyright 2023 LINE Corporation
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
  Divider,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  HStack,
  IconButton,
  Input,
  InputGroup,
  InputLeftElement,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  Tag,
  Textarea,
  VStack,
} from '@chakra-ui/react';
import { HiOutlineIdentification, HiOutlineUser } from 'react-icons/hi';
import { VscRegex } from 'react-icons/vsc';
import { AddIcon } from '@chakra-ui/icons';
import { MdDelete, MdPublic } from 'react-icons/md';
import { GoKey, GoLock } from 'react-icons/go';
import { RiGitRepositoryPrivateLine } from 'react-icons/ri';
import { useFieldArray, useForm } from 'react-hook-form';
import React, { useState } from 'react';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import FieldErrorMessage from 'dogma/common/components/form/FieldErrorMessage';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { FiBox } from 'react-icons/fi';

interface CredentialFormProps {
  projectName: string;
  defaultValue: CredentialDto;
  onSubmit: (credential: CredentialDto, onSuccess: () => void) => Promise<void>;
  isWaitingResponse: boolean;
}

interface CredentialType {
  type: string;
  description: string;
}

const CREDENTIAL_TYPES: CredentialType[] = [
  { type: 'public_key', description: 'SSH public key' },
  { type: 'password', description: 'Password-based' },
  { type: 'access_token', description: 'Access token-based' },
  { type: 'none', description: 'No authentication' },
];

const CredentialForm = ({ projectName, defaultValue, onSubmit, isWaitingResponse }: CredentialFormProps) => {
  const [credentialType, setCredentialType] = useState<string>(defaultValue.type);

  const isNew = defaultValue.id === '';
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
    control,
  } = useForm<CredentialDto>({
    defaultValues: {
      hostnamePatterns: defaultValue.hostnamePatterns,
    },
  });
  const {
    fields: hostnamePatterns,
    append,
    remove,
  } = useFieldArray({
    control,
    // @ts-ignore
    name: 'hostnamePatterns',
  });

  /**
   * Cleans up the dirty fields which is not related to the credential type.
   */
  function filterCredential(credential: CredentialDto): CredentialDto {
    const type = credential.type;
    if (type !== 'public_key') {
      credential.publicKey = undefined;
      credential.privateKey = undefined;
      credential.passphrase = undefined;
    }
    if (type != 'password') {
      credential.password = undefined;
    }
    if (type != 'access_token') {
      credential.accessToken = undefined;
    }
    if (type !== 'public_key' && type !== 'password') {
      credential.username = undefined;
    }
    return credential;
  }

  return (
    <form onSubmit={handleSubmit((credential) => onSubmit(filterCredential(credential), reset))}>
      <Center>
        <VStack width="80%" align="left">
          <Heading size="lg" mb={4}>
            {isNew ? 'New Credential' : 'Edit Credential'}
          </Heading>
          <HStack paddingBottom={2}>
            <LabelledIcon icon={FiBox} text="Project" />
            <Tag fontWeight={'bold'}>{projectName}</Tag>
          </HStack>
          <Divider />
          <FormControl isRequired isInvalid={errors.id != null}>
            <FormLabel>
              <LabelledIcon icon={HiOutlineIdentification} text="Credential ID" />
            </FormLabel>
            <Input
              id="id"
              name="id"
              type="text"
              readOnly={!isNew}
              placeholder="The credential ID"
              defaultValue={defaultValue.id}
              {...register('id', { required: true, pattern: /^[a-zA-Z0-9-_.]+$/ })}
            />
            {errors.id ? (
              <FieldErrorMessage error={errors.id} fieldName="credential ID" />
            ) : (
              <FormHelperText>
                The credential ID must be unique and contain alphanumeric characters, dashes, underscores, and
                periods only.
              </FormHelperText>
            )}
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.type != null}>
            <FormLabel>
              <LabelledIcon icon={GoLock} text="Authentication" />
            </FormLabel>
            <RadioGroup onChange={setCredentialType} value={credentialType}>
              <Stack direction="row">
                {CREDENTIAL_TYPES.map((credentialType) => (
                  <Radio
                    key={credentialType.type}
                    {...register('type')}
                    value={credentialType.type}
                    marginRight={2}
                    defaultChecked={credentialType.type === defaultValue.type}
                  >
                    {credentialType.description}
                  </Radio>
                ))}
              </Stack>
            </RadioGroup>
            <FieldErrorMessage error={errors.type} fieldName="authentication type" />
          </FormControl>
          <Spacer />
          <Divider />
          <Spacer />

          {credentialType === 'public_key' && (
            <>
              <FormControl isRequired isInvalid={errors.username != null}>
                <FormLabel>
                  <LabelledIcon icon={HiOutlineUser} text="Username" />
                </FormLabel>
                <Input
                  id="username"
                  name="username"
                  placeholder="git"
                  defaultValue={defaultValue.username}
                  {...register('username', { required: true })}
                />
                <FieldErrorMessage error={errors.username} />
              </FormControl>
              <Spacer />

              <FormControl isRequired isInvalid={errors.publicKey != null}>
                <FormLabel>
                  <LabelledIcon icon={MdPublic} text="SSH public key" />
                </FormLabel>
                <Textarea
                  id="publicKey"
                  name="puliicKey"
                  fontFamily="monospace"
                  height="3xs"
                  defaultValue={defaultValue.publicKey}
                  placeholder="ecdsa-sha2-nistp521 XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX... user@host"
                  {...register('publicKey', { required: true })}
                />
                <FieldErrorMessage error={errors.publicKey} fieldName="public key" />
              </FormControl>
              <Spacer />

              <FormControl isRequired isInvalid={errors.privateKey != null}>
                <FormLabel>
                  <LabelledIcon icon={RiGitRepositoryPrivateLine} text="SSH private key" />
                </FormLabel>
                <Textarea
                  id="privateKey"
                  height="xs"
                  fontFamily="monospace"
                  name="privateKey"
                  placeholder={`-----BEGIN EC PRIVATE KEY-----
XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
...
-----END EC PRIVATE KEY-----`}
                  {...register('privateKey', { required: true })}
                />
                <FieldErrorMessage error={errors.privateKey} fieldName="private key" />
              </FormControl>
              <Spacer />

              <FormControl>
                <FormLabel>
                  <LabelledIcon icon={GoKey} text="SSH passphrase" />
                </FormLabel>
                <Input
                  id="passphrase"
                  name="passphrase"
                  placeholder="passphrase..."
                  {...register('passphrase')}
                />
              </FormControl>
            </>
          )}

          {credentialType === 'password' && (
            <>
              <FormControl isRequired isInvalid={errors.username != null}>
                <FormLabel>
                  <LabelledIcon icon={HiOutlineUser} text="Username" />
                </FormLabel>
                <Input
                  id="username"
                  name="username"
                  defaultValue={defaultValue.username}
                  placeholder="username"
                  {...register('username', { required: true })}
                />
                <FieldErrorMessage error={errors.username} />
              </FormControl>
              <FormControl isRequired isInvalid={errors.password != null}>
                <FormLabel>
                  <LabelledIcon icon={GoKey} text="Password" />
                </FormLabel>
                <Input
                  id="password"
                  name="password"
                  placeholder="password"
                  {...register('password', { required: true })}
                />
                <FieldErrorMessage error={errors.password} />
              </FormControl>
            </>
          )}

          {credentialType === 'access_token' && (
            <FormControl isRequired isInvalid={errors.accessToken != null}>
              <FormLabel>
                <LabelledIcon icon={GoKey} text="Access Token" />
              </FormLabel>
              <Input
                id="accessToken"
                name="accessToken"
                placeholder="YOUR_SECRET_ACCESS_TOKEN"
                {...register('accessToken', { required: true })}
              />
              <FieldErrorMessage error={errors.accessToken} fieldName="access token" />
            </FormControl>
          )}

          {credentialType === 'none' && <>{/* No additional information is required */}</>}
          <Spacer />

          <FormControl>
            <FormLabel>
              <LabelledIcon icon={VscRegex} text="Hostname patterns" />
              <IconButton
                aria-label="Add"
                color="teal.500"
                variant="ghost"
                icon={<AddIcon />}
                ml={1}
                mb={0.5}
                size={'sm'}
                onClick={() => append('')}
              />
            </FormLabel>
            {hostnamePatterns.map((item, index) => {
              return (
                <>
                  <InputGroup>
                    <Input
                      id="hostnamePatterns"
                      name="hostnamePatterns"
                      type="text"
                      placeholder="^git\.repo\.com$"
                      {...register(`hostnamePatterns.${index}`, { required: true })}
                    />
                    <InputLeftElement>
                      <IconButton
                        aria-label="Remove"
                        color={'red.200'}
                        variant="ghost"
                        icon={<MdDelete />}
                        mr={1}
                        size={'sm'}
                        onClick={() => remove(index)}
                      ></IconButton>
                    </InputLeftElement>
                  </InputGroup>
                </>
              );
            })}
            <FormHelperText>
              The credential whose hostname pattern matches first will be used when accessing a host.
            </FormHelperText>
          </FormControl>
          <Spacer />
          {isNew ? (
            <Button type="submit" colorScheme="blue" loadingText="Creating">
              Create a new credential
            </Button>
          ) : (
            <Button type="submit" colorScheme="green" isLoading={isWaitingResponse} loadingText="Updating">
              Update the credential
            </Button>
          )}
        </VStack>
      </Center>
    </form>
  );
};

export default CredentialForm;
