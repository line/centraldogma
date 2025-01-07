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

import { Controller, useForm, UseFormSetError } from 'react-hook-form';
import {
  Alert,
  AlertIcon,
  Button,
  Center,
  Divider,
  FormControl,
  FormHelperText,
  FormLabel,
  Heading,
  HStack,
  Input,
  Link,
  Radio,
  RadioGroup,
  Spacer,
  Stack,
  Switch,
  Tag,
  Textarea,
  VStack,
} from '@chakra-ui/react';
import { LabelledIcon } from 'dogma/common/components/LabelledIcon';
import { GiMirrorMirror, GiPowerButton } from 'react-icons/gi';
import { BiTimer } from 'react-icons/bi';
import { ExternalLinkIcon } from '@chakra-ui/icons';
import { GoArrowBoth, GoArrowDown, GoArrowUp, GoKey, GoRepo } from 'react-icons/go';
import { Select } from 'chakra-react-select';
import { IoBanSharp } from 'react-icons/io5';
import { useGetCredentialsQuery, useGetMirrorConfigQuery, useGetReposQuery } from 'dogma/features/api/apiSlice';
import React, { useMemo, useState } from 'react';
import FieldErrorMessage from 'dogma/common/components/form/FieldErrorMessage';
import { RepoDto } from 'dogma/features/repo/RepoDto';
import { MirrorDto } from 'dogma/features/project/settings/mirrors/MirrorDto';
import { CredentialDto } from 'dogma/features/project/settings/credentials/CredentialDto';
import { FiBox } from 'react-icons/fi';
import cronstrue from 'cronstrue';
import { CiLocationOn } from 'react-icons/ci';

interface MirrorFormProps {
  projectName: string;
  defaultValue: MirrorDto;
  onSubmit: (mirror: MirrorDto, onSuccess: () => void, setError: UseFormSetError<MirrorDto>) => Promise<void>;
  isWaitingResponse: boolean;
}

interface OptionType {
  value: string;
  label: string;
}

const MIRROR_SCHEMES: OptionType[] = ['git+ssh', 'git+http', 'git+https'].map((scheme) => ({
  value: scheme,
  label: scheme,
}));

const INTERNAL_REPOS = new Set<string>(['dogma', 'meta']);

const MirrorForm = ({ projectName, defaultValue, onSubmit, isWaitingResponse }: MirrorFormProps) => {
  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
    setError,
    setValue,
    control,
    watch,
  } = useForm<MirrorDto>({
    defaultValues: defaultValue,
  });

  const isNew = defaultValue.id === '';
  const { data: repos } = useGetReposQuery(projectName);
  const { data: credentials } = useGetCredentialsQuery(projectName);
  const { data: zoneConfig } = useGetMirrorConfigQuery();

  const [isScheduleEnabled, setScheduleEnabled] = useState<boolean>(defaultValue.schedule != null);
  const schedule = watch('schedule');

  const repoOptions: OptionType[] = (repos || [])
    .filter((repo: RepoDto) => !INTERNAL_REPOS.has(repo.name))
    .map((repo: RepoDto) => ({
      value: repo.name,
      label: repo.name,
    }));

  const credentialOptions: OptionType[] = (credentials || [])
    .filter((credential: CredentialDto) => credential.id)
    .map((credential: CredentialDto) => ({
      value: credential.id,
      label: credential.id,
    }));

  const zoneOptions: OptionType[] = (zoneConfig?.zonePinned ? zoneConfig.zone.allZones : []).map(
    (zone: string) => ({
      value: zone,
      label: zone,
    }),
  );

  useMemo(() => {
    // `defaultValue` property is not working when using `react-select` with `react-hook-form`. So we have to
    // set the value manually. https://stackoverflow.com/a/66723262/1736581
    if (!isNew) {
      setValue('localRepo', defaultValue.localRepo);
      setValue('remoteScheme', defaultValue.remoteScheme);
      setValue('credentialId', defaultValue.credentialId);
      setValue('direction', defaultValue.direction);
      setValue('zone', defaultValue.zone);
    }
  }, [
    isNew,
    setValue,
    defaultValue.localRepo,
    defaultValue.remoteScheme,
    defaultValue.credentialId,
    defaultValue.direction,
    defaultValue.zone,
  ]);

  const defaultRemoteScheme: OptionType = defaultValue.remoteScheme
    ? { value: defaultValue.remoteScheme, label: defaultValue.remoteScheme }
    : null;
  const defaultCredential: OptionType = defaultValue.credentialId
    ? { value: defaultValue.credentialId, label: defaultValue.credentialId }
    : null;
  const defaultZone: OptionType = defaultValue.zone
    ? { value: defaultValue.zone, label: defaultValue.zone }
    : null;

  return (
    <form
      onSubmit={handleSubmit((mirror) => {
        return onSubmit(mirror, () => {}, setError);
      })}
    >
      <Center>
        <VStack width="80%" align="left">
          <Heading size="lg" mb={4}>
            {isNew ? 'New Mirror' : 'Edit Mirror'}
          </Heading>
          <HStack paddingBottom={2}>
            <LabelledIcon icon={FiBox} text="Project" />
            <Tag fontWeight={'bold'}>{projectName}</Tag>
          </HStack>
          <Divider />
          <FormControl isRequired isInvalid={errors.id != null}>
            <FormLabel>
              <LabelledIcon icon={GiMirrorMirror} text="Mirror ID" />
            </FormLabel>
            <Input
              id="id"
              name="id"
              type="text"
              readOnly={!isNew}
              defaultValue={defaultValue.id}
              placeholder="The mirror ID"
              {...register('id', { required: true, pattern: /^[a-zA-Z0-9-_.]+$/ })}
            />
            {errors.id ? (
              <FieldErrorMessage error={errors.id} fieldName="ID" />
            ) : (
              <FormHelperText>
                The mirror ID must be unique and contain alphanumeric characters, dashes, underscores, and
                periods only.
              </FormHelperText>
            )}
          </FormControl>
          <Spacer />

          <FormControl isInvalid={errors.schedule != null}>
            <FormLabel>
              <LabelledIcon icon={BiTimer} text="Schedule" />
              <Switch
                marginLeft={3}
                id="enableSchedule"
                defaultChecked={defaultValue.schedule != null}
                onChange={(e) => {
                  if (e.target.checked) {
                    setValue('schedule', defaultValue.schedule, {
                      shouldDirty: true,
                    });
                    setScheduleEnabled(true);
                  } else {
                    setValue('schedule', null, {
                      shouldDirty: true,
                    });
                    setScheduleEnabled(false);
                  }
                }}
              />
            </FormLabel>
            {isScheduleEnabled ? (
              <>
                <Input
                  id="schedule"
                  name="schedule"
                  type="text"
                  placeholder="0 * * * * ?"
                  defaultValue={defaultValue.schedule}
                  {...register('schedule', { required: true })}
                />
                {schedule && (
                  <FormHelperText color={'gray.500'}>
                    {cronstrue.toString(schedule, { verbose: true })}
                  </FormHelperText>
                )}
              </>
            ) : (
              <Alert status="warning" marginTop={3} borderRadius={5}>
                <AlertIcon />
                Scheduling is disabled.
              </Alert>
            )}

            {errors.schedule ? (
              <FieldErrorMessage error={errors.schedule} />
            ) : (
              isScheduleEnabled && (
                <FormHelperText>
                  <Link
                    color="teal.500"
                    href="https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/crontrigger.html"
                    isExternal
                  >
                    Quartz cron expression <ExternalLinkIcon mx="2px" />{' '}
                  </Link>
                  is used to describe when the mirroring task is supposed to be triggered.
                </FormHelperText>
              )
            )}
          </FormControl>
          <Spacer />

          <FormControl isRequired isInvalid={errors.direction != null}>
            <FormLabel>
              <LabelledIcon icon={GoArrowBoth} text="Direction" />
            </FormLabel>
            <Controller
              name="direction"
              rules={{ required: true }}
              control={control}
              render={({ field: { onChange, value } }) => (
                <RadioGroup onChange={onChange} value={value} defaultValue={defaultValue.direction}>
                  <Stack direction="row">
                    <Radio value="REMOTE_TO_LOCAL" marginRight={2}>
                      <LabelledIcon icon={GoArrowDown} text="Remote to Central Dogma" />
                    </Radio>
                    <Radio value="LOCAL_TO_REMOTE">
                      <LabelledIcon icon={GoArrowUp} text="Central Dogma to Remote" />
                    </Radio>
                  </Stack>
                </RadioGroup>
              )}
            />
            <FieldErrorMessage error={errors.direction} />
          </FormControl>
          <Spacer />

          <Stack direction="row" width="100%">
            <FormControl isRequired isInvalid={errors.localRepo != null}>
              <FormLabel>
                <LabelledIcon icon={GoRepo} text={'Local repository'} />
              </FormLabel>
              <Controller
                control={control}
                name="localRepo"
                rules={{ required: true }}
                render={({ field: { onChange, value, name, ref } }) => (
                  <Select
                    ref={ref}
                    id="localRepo"
                    name={name}
                    isDisabled={repoOptions.length === 0}
                    options={repoOptions}
                    // The default value of React Select must be null (and not undefined)
                    value={repoOptions.find((option) => option.value === value) || null}
                    onChange={(option) => onChange(option?.value || '')}
                    placeholder={
                      repoOptions.length === 0
                        ? 'No local repository is found. You need to create a local repository first.'
                        : 'Enter repo name...'
                    }
                    closeMenuOnSelect={true}
                    openMenuOnFocus={true}
                    isSearchable={true}
                    isClearable={true}
                  />
                )}
              />
              <FieldErrorMessage error={errors.localRepo} fieldName="Local repository" />
            </FormControl>
            <FormControl width="50%" isRequired isInvalid={errors.localPath != null}>
              <FormLabel>path</FormLabel>
              <Input
                id="localPath"
                name="localPath"
                type="text"
                defaultValue={defaultValue.localPath}
                placeholder="/"
                {...register('localPath', { required: true })}
              />
              <FieldErrorMessage error={errors.localPath} fieldName="Local path" />
            </FormControl>
          </Stack>
          <Spacer />

          <Stack direction="row" width="100%">
            <FormControl width="50%" isRequired isInvalid={errors.remoteScheme != null}>
              <FormLabel>
                <LabelledIcon icon={GoRepo} text={'Remote'} />
              </FormLabel>
              <Controller
                control={control}
                name="remoteScheme"
                rules={{ required: true }}
                render={({ field: { onChange, value, name, ref } }) => (
                  <Select
                    ref={ref}
                    id="remoteScheme"
                    name={name}
                    options={MIRROR_SCHEMES}
                    defaultValue={defaultRemoteScheme}
                    // The default value of React Select must be null (and not undefined)
                    value={MIRROR_SCHEMES.find((option) => option.value === value) || null}
                    onChange={(option) => onChange(option?.value || '')}
                    placeholder="scheme"
                    closeMenuOnSelect={true}
                    openMenuOnFocus={true}
                    isSearchable={true}
                    isClearable={true}
                  />
                )}
              />
              <FieldErrorMessage error={errors.remoteScheme} fieldName="scheme" />
            </FormControl>
            <FormControl isInvalid={errors.remoteUrl != null} isRequired>
              <FormLabel>repo</FormLabel>
              <Input
                id="remoteUrl"
                name="remoteUrl"
                type="text"
                defaultValue={defaultValue.remoteUrl}
                placeholder="my.git.com/org/myrepo.git"
                {...register('remoteUrl', { required: true, pattern: /^[\w.\-]+(:[0-9]+)?\/[\w.\-\/]+.git$/ })}
              />
              <FieldErrorMessage
                error={errors.remoteUrl}
                fieldName="remote URL"
                errorMessage="Invalid remote URL. (expected format: 'my.git.com/org/myrepo.git')"
              />
            </FormControl>
            <FormControl width="50%" isRequired isInvalid={errors.remoteBranch != null}>
              <FormLabel>branch</FormLabel>
              <Input
                id="remoteBranch"
                name="remoteBranch"
                type="text"
                defaultValue={defaultValue.remoteBranch}
                placeholder="main"
                {...register('remoteBranch', { required: true })}
              />
              <FieldErrorMessage error={errors.remoteBranch} fieldName="remote branch" />
            </FormControl>
            <FormControl width="50%" isRequired isInvalid={errors.remotePath != null}>
              <FormLabel>path</FormLabel>
              <Input
                id="remotePath"
                name="remotePath"
                type="text"
                defaultValue={defaultValue.remotePath}
                placeholder="/"
                {...register('remotePath', { required: true })}
              />
              <FieldErrorMessage error={errors.remotePath} fieldName="remote path" />
            </FormControl>
          </Stack>
          <Spacer />

          <FormControl width="65%" alignItems="left" isInvalid={errors.credentialId != null}>
            <FormLabel>
              <LabelledIcon icon={GoKey} text={'Credential'} />
            </FormLabel>
            <Controller
              control={control}
              name="credentialId"
              rules={{ required: true }}
              render={({ field: { onChange, value, name, ref } }) => (
                <Select
                  ref={ref}
                  id="credentialId"
                  name={name}
                  isDisabled={credentialOptions.length === 0}
                  options={credentialOptions}
                  defaultValue={defaultCredential}
                  // The default value of React Select must be null (and not undefined)
                  value={credentialOptions.find((option) => option.value === value) || null}
                  onChange={(option) => onChange(option?.value || '')}
                  placeholder={
                    credentialOptions.length === 0
                      ? 'No credential is found. You need to create credentials first.'
                      : 'Enter credential ID ...'
                  }
                  closeMenuOnSelect={true}
                  openMenuOnFocus={true}
                  isSearchable={true}
                  isClearable={true}
                />
              )}
            />
            <FieldErrorMessage error={errors.credentialId} fieldName="Credential" />
          </FormControl>
          <Spacer />
          {zoneConfig?.zonePinned && (
            <>
              <FormControl width="65%" isRequired alignItems="left" isInvalid={errors.zone != null}>
                <FormLabel>
                  <LabelledIcon icon={CiLocationOn} text={'Zone'} />
                </FormLabel>
                <Controller
                  control={control}
                  name="zone"
                  rules={{ required: true }}
                  render={({ field: { onChange, value, name, ref } }) => (
                    <Select
                      ref={ref}
                      id="zone"
                      name={name}
                      options={zoneOptions}
                      defaultValue={defaultZone}
                      // The default value of React Select must be null (and not undefined)
                      value={zoneOptions.find((option) => option.value === value) || null}
                      onChange={(option) => onChange(option?.value || '')}
                      placeholder={'Enter zone ...'}
                      closeMenuOnSelect={true}
                      openMenuOnFocus={true}
                      isSearchable={true}
                      isClearable={true}
                    />
                  )}
                />
                <FieldErrorMessage error={errors.zone} fieldName="Zone" />
                <FormHelperText>The zone where the mirror will be executed.</FormHelperText>
              </FormControl>
              <Spacer />
            </>
          )}

          <FormControl>
            <FormLabel>
              <LabelledIcon icon={IoBanSharp} text={'gitignore'} />
            </FormLabel>
            <Textarea
              id="gitignore"
              name="gitignore"
              defaultValue={defaultValue.gitignore}
              placeholder=""
              {...register('gitignore')}
            />
            <FormHelperText>
              <Link color="teal.500" href="https://git-scm.com/docs/gitignore" isExternal>
                gitignore <ExternalLinkIcon mx="2px" />
              </Link>{' '}
              describes files that should be excluded from the mirroring.
            </FormHelperText>
          </FormControl>
          <Spacer />

          <FormControl display="flex" alignItems="center">
            <FormLabel htmlFor="enabled" mb="0">
              <LabelledIcon icon={GiPowerButton} text={'Enable mirror?'} />
            </FormLabel>
            <Switch id="enabled" defaultChecked={defaultValue.enabled} {...register('enabled')} />
          </FormControl>
          <Spacer />
          <Spacer />

          {isNew ? (
            <Button
              type="submit"
              colorScheme="blue"
              isLoading={isWaitingResponse}
              loadingText="Creating"
              marginTop="10px"
            >
              Create a new mirror
            </Button>
          ) : (
            <Button
              type="submit"
              colorScheme="green"
              isDisabled={!isDirty}
              isLoading={isWaitingResponse}
              loadingText="Updating"
              marginTop="10px"
            >
              Update the mirror
            </Button>
          )}
        </VStack>
      </Center>
    </form>
  );
};

export default MirrorForm;
