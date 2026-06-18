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
  Box,
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  Button,
  Checkbox,
  Divider,
  Flex,
  FormControl,
  FormErrorMessage,
  FormLabel,
  Heading,
  HStack,
  IconButton,
  Input,
  SimpleGrid,
  Spacer,
  Text,
  useDisclosure,
} from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import Router from 'next/router';
import { useEffect, useState } from 'react';
import { Control, Controller, FieldErrors, useFieldArray, useForm, UseFormRegister } from 'react-hook-form';
import { OptionBase, Select } from 'chakra-react-select';
import { AiOutlineClose, AiOutlineDelete, AiOutlineEdit } from 'react-icons/ai';
import { FiSave } from 'react-icons/fi';
import { IoAddCircleOutline } from 'react-icons/io5';
import { Deferred } from 'dogma/common/components/Deferred';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import {
  FileContentDto,
  useCreateK8sAggregatorMutation,
  useDeleteK8sAggregatorMutation,
  useGetK8sAggregatorQuery,
  useListCredentialsQuery,
  useUpdateK8sAggregatorMutation,
} from 'dogma/features/xds/xdsApiSlice';
import { useGroupWriteAccess } from 'dogma/common/useGroupWriteAccess';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

// Matches the server-side resource id pattern (XdsResourceManager.RESOURCE_ID_PATTERN_STRING).
const AGGREGATOR_ID_PATTERN = /^[a-z](?:[a-z0-9-_/]*[a-z0-9])?$/;

interface PropertyForm {
  key: string;
  value: string;
}

interface WatcherForm {
  serviceName: string;
  portName: string;
  controlPlaneUrl: string;
  namespace: string;
  credentialId: string;
  trustCerts: boolean;
  priority: string;
  loadBalancingWeight: string;
  region: string;
  zone: string;
  subZone: string;
  additionalProperties: PropertyForm[];
}

interface FormData {
  aggregatorId: string;
  watchers: WatcherForm[];
}

const emptyWatcher: WatcherForm = {
  serviceName: '',
  portName: '',
  controlPlaneUrl: '',
  namespace: '',
  credentialId: '',
  trustCerts: false,
  priority: '',
  loadBalancingWeight: '',
  region: '',
  zone: '',
  subZone: '',
  additionalProperties: [],
};

function buildBody(data: FormData, name?: string): string {
  const localityLbEndpoints = data.watchers.map((w) => {
    const kubeconfig: Record<string, unknown> = { controlPlaneUrl: w.controlPlaneUrl.trim() };
    if (w.namespace.trim()) {
      kubeconfig.namespace = w.namespace.trim();
    }
    if (w.credentialId.trim()) {
      kubeconfig.credentialId = w.credentialId.trim();
    }
    if (w.trustCerts) {
      kubeconfig.trustCerts = true;
    }
    const watcher: Record<string, unknown> = { serviceName: w.serviceName.trim(), kubeconfig };
    if (w.portName.trim()) {
      watcher.portName = w.portName.trim();
    }
    const additionalProperties: Record<string, string> = {};
    w.additionalProperties.forEach((p) => {
      if (p.key.trim()) {
        additionalProperties[p.key.trim()] = p.value;
      }
    });
    if (Object.keys(additionalProperties).length > 0) {
      watcher.additionalProperties = additionalProperties;
    }
    const entry: Record<string, unknown> = { watcher };
    const locality: Record<string, string> = {};
    if (w.region.trim()) {
      locality.region = w.region.trim();
    }
    if (w.zone.trim()) {
      locality.zone = w.zone.trim();
    }
    if (w.subZone.trim()) {
      locality.subZone = w.subZone.trim();
    }
    if (Object.keys(locality).length > 0) {
      entry.locality = locality;
    }
    if (w.priority.trim()) {
      entry.priority = Number(w.priority);
    }
    if (w.loadBalancingWeight.trim()) {
      entry.loadBalancingWeight = Number(w.loadBalancingWeight);
    }
    return entry;
  });
  const body: Record<string, unknown> = { localityLbEndpoints };
  if (name) {
    body.name = name;
  }
  return JSON.stringify(body);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function parseToFormData(aggregatorId: string, content: any): FormData {
  const endpoints = Array.isArray(content?.localityLbEndpoints) ? content.localityLbEndpoints : [];
  const watchers: WatcherForm[] = endpoints.map(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (e: any) => ({
      serviceName: e?.watcher?.serviceName ?? '',
      portName: e?.watcher?.portName ?? '',
      controlPlaneUrl: e?.watcher?.kubeconfig?.controlPlaneUrl ?? '',
      namespace: e?.watcher?.kubeconfig?.namespace ?? '',
      credentialId: e?.watcher?.kubeconfig?.credentialId ?? '',
      trustCerts: !!e?.watcher?.kubeconfig?.trustCerts,
      priority: e?.priority != null ? String(e.priority) : '',
      loadBalancingWeight: e?.loadBalancingWeight != null ? String(e.loadBalancingWeight) : '',
      region: e?.locality?.region ?? '',
      zone: e?.locality?.zone ?? '',
      subZone: e?.locality?.subZone ?? '',
      additionalProperties: Object.entries(e?.watcher?.additionalProperties ?? {}).map(([key, value]) => ({
        key,
        value: String(value),
      })),
    }),
  );
  return { aggregatorId, watchers: watchers.length > 0 ? watchers : [{ ...emptyWatcher }] };
}

interface CredentialOption extends OptionBase {
  value: string;
  label: string;
}

interface WatcherFieldsProps {
  index: number;
  control: Control<FormData>;
  register: UseFormRegister<FormData>;
  serviceNameError: boolean;
  controlPlaneUrlError: boolean;
  // The group's access-token credential ids to choose from, or null when they cannot be listed
  // (e.g. the user lacks the ADMIN role required by the credential API) — in which case a free-text input is
  // shown so the id can still be entered.
  credentialOptions: string[] | null;
  onRemove: () => void;
  canRemove: boolean;
  readOnly: boolean;
}

const WatcherFields = ({
  index,
  control,
  register,
  serviceNameError,
  controlPlaneUrlError,
  credentialOptions,
  onRemove,
  canRemove,
  readOnly,
}: WatcherFieldsProps) => {
  const { fields, append, remove } = useFieldArray({
    control,
    name: `watchers.${index}.additionalProperties` as `watchers.${number}.additionalProperties`,
  });
  return (
    <Box borderWidth="1px" borderRadius="md" p={4} mb={4} maxW="3xl">
      <Flex mb={2} align="center">
        <Text fontWeight="bold">Watcher #{index + 1}</Text>
        <Spacer />
        {canRemove && !readOnly && (
          <Button size="xs" variant="ghost" colorScheme="red" leftIcon={<AiOutlineDelete />} onClick={onRemove}>
            Remove
          </Button>
        )}
      </Flex>
      <SimpleGrid columns={2} spacing={3}>
        <FormControl isInvalid={serviceNameError} isRequired>
          <FormLabel fontSize="sm">Service name</FormLabel>
          <Input
            size="sm"
            placeholder="k8s service name"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.serviceName`, { required: true })}
          />
          <FormErrorMessage>Service name is required.</FormErrorMessage>
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Port name</FormLabel>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.portName`)}
          />
        </FormControl>
        <FormControl isInvalid={controlPlaneUrlError} isRequired>
          <FormLabel fontSize="sm">Control plane URL</FormLabel>
          <Input
            size="sm"
            placeholder="https://kubernetes.default.svc"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.controlPlaneUrl`, { required: true })}
          />
          <FormErrorMessage>Control plane URL is required.</FormErrorMessage>
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Namespace</FormLabel>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.namespace`)}
          />
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Credential ID</FormLabel>
          {credentialOptions !== null ? (
            <Controller
              control={control}
              name={`watchers.${index}.credentialId`}
              render={({ field: { onChange, value, name, ref } }) => {
                const ids = value ? [...new Set([...credentialOptions, value])] : credentialOptions;
                const options: CredentialOption[] = ids.map((id) => ({ value: id, label: id }));
                return (
                  <Select<CredentialOption>
                    ref={ref}
                    name={name}
                    size="sm"
                    options={options}
                    // react-select requires null (not undefined) for an empty value.
                    value={options.find((o) => o.value === value) || null}
                    onChange={(option) => onChange(option ? option.value : '')}
                    placeholder="select a credential (optional)"
                    closeMenuOnSelect
                    isClearable
                    isSearchable
                    isDisabled={readOnly}
                  />
                );
              }}
            />
          ) : (
            <Input
              size="sm"
              placeholder="optional"
              isReadOnly={readOnly}
              {...register(`watchers.${index}.credentialId`)}
            />
          )}
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Priority</FormLabel>
          <Input
            size="sm"
            type="number"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.priority`)}
          />
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Load balancing weight</FormLabel>
          <Input
            size="sm"
            type="number"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.loadBalancingWeight`)}
          />
        </FormControl>
        <FormControl display="flex" alignItems="center" pt={6}>
          <Checkbox isReadOnly={readOnly} {...register(`watchers.${index}.trustCerts`)}>
            Trust certificates
          </Checkbox>
        </FormControl>
      </SimpleGrid>

      <Text mt={4} mb={1} fontSize="sm" fontWeight="semibold" color="gray.500">
        Locality (optional)
      </Text>
      <SimpleGrid columns={3} spacing={3}>
        <FormControl>
          <FormLabel fontSize="sm">Region</FormLabel>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.region`)}
          />
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Zone</FormLabel>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.zone`)}
          />
        </FormControl>
        <FormControl>
          <FormLabel fontSize="sm">Sub zone</FormLabel>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.subZone`)}
          />
        </FormControl>
      </SimpleGrid>

      <Text mt={4} mb={1} fontSize="sm" fontWeight="semibold" color="gray.500">
        Additional properties (optional)
      </Text>
      {fields.map((field, propIndex) => (
        <HStack key={field.id} mb={2}>
          <Input
            size="sm"
            placeholder="key"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.additionalProperties.${propIndex}.key`)}
          />
          <Input
            size="sm"
            placeholder="value"
            isReadOnly={readOnly}
            {...register(`watchers.${index}.additionalProperties.${propIndex}.value`)}
          />
          {!readOnly && (
            <IconButton
              size="sm"
              variant="ghost"
              colorScheme="red"
              aria-label="Remove property"
              icon={<AiOutlineDelete />}
              onClick={() => remove(propIndex)}
            />
          )}
        </HStack>
      ))}
      {!readOnly && (
        <Button
          size="xs"
          variant="outline"
          leftIcon={<IoAddCircleOutline />}
          onClick={() => append({ key: '', value: '' })}
        >
          Add property
        </Button>
      )}
    </Box>
  );
};

interface AggregatorFormFieldsProps {
  group: string;
  control: Control<FormData>;
  register: UseFormRegister<FormData>;
  errors: FieldErrors<FormData>;
  idReadOnly: boolean;
  readOnly: boolean;
}

const AggregatorFormFields = ({
  group,
  control,
  register,
  errors,
  idReadOnly,
  readOnly,
}: AggregatorFormFieldsProps) => {
  const { fields, append, remove } = useFieldArray({ control, name: 'watchers' });
  // Offer the group's access-token credentials as a dropdown. Listing requires the ADMIN role, so on any error
  // (e.g. 403 for non-admins) fall back to a free-text credential id input.
  const { data: credentials, error: credentialsError } = useListCredentialsQuery({ group });
  const credentialOptions: string[] | null = credentialsError
    ? null
    : (credentials || []).filter((c) => c.type === 'ACCESS_TOKEN').map((c) => c.id);
  return (
    <>
      <FormControl isInvalid={!!errors.aggregatorId} isRequired mb={4} maxW="md">
        <FormLabel>Aggregator ID</FormLabel>
        <Input
          placeholder="e.g. my-service"
          isReadOnly={idReadOnly || readOnly}
          {...register('aggregatorId', { required: true, pattern: AGGREGATOR_ID_PATTERN })}
        />
        <FormErrorMessage>ID must match [a-z](?:[a-z0-9-_/]*[a-z0-9])?</FormErrorMessage>
      </FormControl>

      {fields.map((field, index) => (
        <WatcherFields
          key={field.id}
          index={index}
          control={control}
          register={register}
          serviceNameError={!!errors.watchers?.[index]?.serviceName}
          controlPlaneUrlError={!!errors.watchers?.[index]?.controlPlaneUrl}
          credentialOptions={credentialOptions}
          onRemove={() => remove(index)}
          canRemove={fields.length > 1}
          readOnly={readOnly}
        />
      ))}
      {!readOnly && (
        <Button
          size="sm"
          variant="outline"
          leftIcon={<IoAddCircleOutline />}
          onClick={() => append({ ...emptyWatcher })}
        >
          Add watcher
        </Button>
      )}
    </>
  );
};

const NewK8sAggregatorEditor = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  const [createAggregator, { isLoading }] = useCreateK8sAggregatorMutation();
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({ defaultValues: { aggregatorId: '', watchers: [{ ...emptyWatcher }] } });

  const onSubmit = async (data: FormData) => {
    try {
      await createAggregator({ group, aggregatorId: data.aggregatorId, body: buildBody(data) }).unwrap();
      dispatch(
        newNotification('Aggregator created', `Aggregator '${data.aggregatorId}' is created`, 'success'),
      );
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`);
    } catch (err) {
      dispatch(newNotification('Failed to create the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  return (
    <Box>
      <AggregatorFormFields
        group={group}
        control={control}
        register={register}
        errors={errors}
        idReadOnly={false}
        readOnly={false}
      />
      <Divider my={4} maxW="3xl" />
      <Flex maxW="3xl">
        <Spacer />
        <Button colorScheme="teal" leftIcon={<FiSave />} onClick={handleSubmit(onSubmit)} isLoading={isLoading}>
          Create
        </Button>
      </Flex>
    </Box>
  );
};

const ExistingK8sAggregatorEditor = ({ group, id }: { group: string; id: string }) => {
  const dispatch = useAppDispatch();
  // Edit/Delete are shown only to users with WRITE on the group.
  const { hasWrite } = useGroupWriteAccess(group);
  const { data, isLoading, error } = useGetK8sAggregatorQuery(
    { group, id },
    { refetchOnMountOrArgChange: true },
  );
  const [updateAggregator, { isLoading: isSaving }] = useUpdateK8sAggregatorMutation();
  const [deleteAggregator, { isLoading: isDeleting }] = useDeleteK8sAggregatorMutation();
  const { isOpen, onOpen, onClose } = useDisclosure();
  // An aggregator opens in read-only view; the user must click Edit to modify it (like the resource editors).
  const [editing, setEditing] = useState(false);
  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({ defaultValues: { aggregatorId: id, watchers: [{ ...emptyWatcher }] } });

  // Sync the form to the latest fetched content, but never while editing so a background refetch cannot
  // clobber unsaved edits.
  useEffect(() => {
    if (data && !editing) {
      reset(parseToFormData(id, (data as FileContentDto).content));
    }
  }, [data, id, reset, editing]);

  const onSubmit = async (formData: FormData) => {
    const name = `groups/${group}/k8s/endpointAggregators/${id}`;
    try {
      await updateAggregator({ group, id, body: buildBody(formData, name) }).unwrap();
      dispatch(newNotification('Aggregator updated', `Aggregator '${id}' is updated`, 'success'));
      setEditing(false);
    } catch (err) {
      dispatch(newNotification('Failed to update the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  const handleCancel = () => {
    if (data) {
      reset(parseToFormData(id, (data as FileContentDto).content));
    }
    setEditing(false);
  };

  const handleDelete = async () => {
    try {
      await deleteAggregator({ group, id }).unwrap();
      dispatch(newNotification('Aggregator deleted', `Aggregator '${id}' is deleted`, 'success'));
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`);
    } catch (err) {
      dispatch(newNotification('Failed to delete the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box>
          <Flex mb={2} maxW="3xl" align="center">
            <Spacer />
            {hasWrite &&
              (editing ? (
                <Button
                  variant="outline"
                  colorScheme="gray"
                  leftIcon={<AiOutlineClose />}
                  size="sm"
                  onClick={handleCancel}
                >
                  Cancel
                </Button>
              ) : (
                <HStack spacing={3}>
                  <Button
                    colorScheme="teal"
                    leftIcon={<AiOutlineEdit />}
                    size="sm"
                    onClick={() => setEditing(true)}
                  >
                    Edit
                  </Button>
                  <Button colorScheme="red" leftIcon={<AiOutlineDelete />} size="sm" onClick={onOpen}>
                    Delete
                  </Button>
                </HStack>
              ))}
          </Flex>
          <AggregatorFormFields
            group={group}
            control={control}
            register={register}
            errors={errors}
            idReadOnly
            readOnly={!editing}
          />
          {editing && hasWrite && (
            <>
              <Divider my={4} maxW="3xl" />
              <Flex maxW="3xl">
                <Spacer />
                <Button
                  colorScheme="teal"
                  leftIcon={<FiSave />}
                  onClick={handleSubmit(onSubmit)}
                  isLoading={isSaving}
                >
                  Save
                </Button>
              </Flex>
            </>
          )}
          <DeleteConfirmationModal
            isOpen={isOpen}
            onClose={onClose}
            type="aggregator"
            id={id}
            from={group}
            handleDelete={handleDelete}
            isLoading={isDeleting}
          />
        </Box>
      )}
    </Deferred>
  );
};

export const K8sAggregatorEditor = ({ group, id, isNew }: { group: string; id?: string; isNew: boolean }) => {
  const title = isNew || !id ? 'New K8s Aggregator' : id;
  const listHref = `/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`;
  return (
    <Box p="2">
      <Breadcrumb mb={4} color="gray.500">
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href="/app/xds">
            Groups
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href={listHref}>
            {group}
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <BreadcrumbLink as={RouteLink} href={listHref}>
            K8s Aggregators
          </BreadcrumbLink>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <BreadcrumbLink href="#">{title}</BreadcrumbLink>
        </BreadcrumbItem>
      </Breadcrumb>
      <Heading size="lg" mb={6}>
        <HStack color="teal">
          <Box>Kubernetes Endpoint Aggregator</Box>
        </HStack>
      </Heading>
      {isNew || !id ? (
        <NewK8sAggregatorEditor group={group} />
      ) : (
        <ExistingK8sAggregatorEditor group={group} id={id} />
      )}
    </Box>
  );
};
