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
  Alert,
  AlertIcon,
  Box,
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  Button,
  Badge,
  Checkbox,
  Divider,
  Flex,
  FormControl,
  FormErrorMessage,
  FormHelperText,
  FormHelperTextProps,
  FormLabel,
  FormLabelProps,
  Heading,
  HStack,
  IconButton,
  Input,
  Select as ChakraSelect,
  SimpleGrid,
  Spacer,
  Stack,
  Text,
  Tooltip,
  useColorModeValue,
  useDisclosure,
} from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import * as jsYaml from 'js-yaml';
import { default as RouteLink } from 'next/link';
import Router from 'next/router';
import { ReactNode, useEffect, useState } from 'react';
import {
  Control,
  Controller,
  FieldErrors,
  useFieldArray,
  useForm,
  UseFormRegister,
  UseFormSetValue,
  UseFormGetValues,
  useWatch,
} from 'react-hook-form';
import { OptionBase, Select } from 'chakra-react-select';
import { AiOutlineClose, AiOutlineDelete, AiOutlineEdit, AiOutlineEye } from 'react-icons/ai';
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
  usePreviewK8sAggregatorMutation,
  useUpdateK8sAggregatorMutation,
} from 'dogma/features/xds/xdsApiSlice';
import { K8sAggregatorPreviewModal, K8sPreviewResult } from 'dogma/features/xds/K8sAggregatorPreviewModal';
import { EditorActionBar } from 'dogma/features/xds/EditorActionBar';
import { useGroupWriteAccess } from 'dogma/features/xds/useGroupWriteAccess';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';
import { K8sAggregatorStatus } from 'dogma/features/xds/K8sAggregatorStatus';

// Matches the server-side resource id pattern (XdsResourceManager.RESOURCE_ID_PATTERN_STRING).
// Dots are allowed (e.g. "my-service.v1"), but slashes are not.
const AGGREGATOR_ID_PATTERN = /^[a-z](?:[a-z0-9_.-]*[a-z0-9])?$/;

// The form holds the aggregator document itself, so a field the form edits is the field that gets saved.
interface DropOverload {
  category?: string;
  dropPercentage?: { numerator?: number; denominator?: string };
}

// A section label that reads as a boundary: the rule carries the eye across, the label sits on it.
const SectionLabel = ({ children, mt = 6 }: { children: ReactNode; mt?: number }) => (
  <Flex align="center" mt={mt} mb={3} gap={3}>
    <Text fontSize="xs" fontWeight="bold" letterSpacing="wide" textTransform="uppercase" whiteSpace="nowrap">
      {children}
    </Text>
    <Divider />
  </Flex>
);

// Chakra's FormLabel is medium; semibold separates it from the hint underneath.
const Label = (props: FormLabelProps) => <FormLabel fontSize="sm" fontWeight="semibold" {...props} />;

// Chakra's FormHelperText defaults to gray.500 / whiteAlpha.600, which is too faint to read in dark mode.
const Help = ({ children, ...props }: FormHelperTextProps) => (
  <FormHelperText fontSize="xs" mt={1} color={useColorModeValue('gray.600', 'gray.400')} {...props}>
    {children}
  </FormHelperText>
);

// A map whose keys are user input cannot be form field names, so the rows live here and the form value
// stays the map itself. Rows are kept in local state to preserve order and blank rows while typing.
const KeyValueEditor = ({
  value,
  onChange,
  readOnly,
}: {
  value?: Record<string, string>;
  onChange: (value: Record<string, string>) => void;
  readOnly: boolean;
}) => {
  const [rows, setRows] = useState<PropertyRow[]>(() =>
    Object.entries(value ?? {}).map(([key, v]) => ({ key, value: String(v) })),
  );
  const update = (next: PropertyRow[]) => {
    setRows(next);
    const map: Record<string, string> = {};
    next.forEach((row) => {
      if (row.key.trim()) {
        map[row.key.trim()] = row.value;
      }
    });
    onChange(map);
  };
  return (
    <>
      {rows.map((row, rowIndex) => (
        <HStack key={rowIndex} mb={2}>
          <Input
            size="sm"
            placeholder="key"
            isReadOnly={readOnly}
            value={row.key}
            onChange={(e) => update(rows.map((r, i) => (i === rowIndex ? { ...r, key: e.target.value } : r)))}
          />
          <Input
            size="sm"
            placeholder="value"
            isReadOnly={readOnly}
            value={row.value}
            onChange={(e) => update(rows.map((r, i) => (i === rowIndex ? { ...r, value: e.target.value } : r)))}
          />
          {!readOnly && (
            <IconButton
              size="sm"
              variant="ghost"
              colorScheme="red"
              aria-label="Remove property"
              icon={<AiOutlineDelete />}
              onClick={() => update(rows.filter((_, i) => i !== rowIndex))}
            />
          )}
        </HStack>
      ))}
      {!readOnly && (
        <Button
          size="xs"
          variant="outline"
          leftIcon={<IoAddCircleOutline />}
          onClick={() => update([...rows, { key: '', value: '' }])}
        >
          Add property
        </Button>
      )}
    </>
  );
};

interface PropertyRow {
  key: string;
  value: string;
}

interface MappingForm {
  resourceType?: string;
  entryType?: string;
  sourceKey?: string;
  sourceKeyPrefix?: string;
  metadataNamespace?: string;
  metadataKey?: string;
}

interface WatcherForm {
  serviceName?: string;
  portName?: string;
  kubeconfig: {
    controlPlaneUrl?: string;
    namespace?: string;
    credentialId?: string;
    trustCerts?: boolean;
  };
  distinctEndpoint?: boolean;
  metadataMapping: MappingForm[];
  additionalProperties?: Record<string, string>;
}

interface LocalityLbEndpointsForm {
  watcher: WatcherForm;
  locality: { region?: string; zone?: string; subZone?: string };
  priority?: number;
  loadBalancingWeight?: number;
}

interface FormData {
  aggregatorId: string;
  localityLbEndpoints: LocalityLbEndpointsForm[];
  policy: {
    overprovisioningFactor?: number;
    weightedPriorityHealth?: boolean;
    endpointStaleAfter?: string;
    // Not editable here — shown read-only and saved back as it was read.
    dropOverloads?: DropOverload[];
  };
  // The revision the form was loaded at. Sent with the update so the server rejects a stale save.
  loadedRevision?: string;
}

const emptyMapping: MappingForm = { resourceType: 'NODE', entryType: 'LABEL' };

const emptyWatcher: LocalityLbEndpointsForm = {
  watcher: { serviceName: '', kubeconfig: {}, metadataMapping: [] },
  locality: {},
};

const emptyPolicy: FormData['policy'] = {};

// Drops what the server would reject or store as noise: blank strings, NaN from a cleared number input, and
// objects or arrays left empty once their own members were dropped.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function pruneEmpty(value: any): any {
  if (Array.isArray(value)) {
    const items = value.map(pruneEmpty).filter((v) => v !== undefined);
    return items.length > 0 ? items : undefined;
  }
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [key, raw] of Object.entries(value)) {
      const pruned = pruneEmpty(raw);
      if (pruned !== undefined) {
        out[key] = pruned;
      }
    }
    return Object.keys(out).length > 0 ? out : undefined;
  }
  if (typeof value === 'string') {
    return value.trim() === '' ? undefined : value.trim();
  }
  if (value === null || value === undefined || (typeof value === 'number' && isNaN(value))) {
    return undefined;
  }
  if (value === false) {
    return undefined;
  }
  return value;
}

function buildBody(data: FormData, name?: string): string {
  const { dropOverloads, ...policy } = data.policy;
  const pruned = pruneEmpty({ localityLbEndpoints: data.localityLbEndpoints, policy, name });
  const body = (pruned ?? {}) as {
    localityLbEndpoints?: Record<string, any>[];
    policy?: Record<string, unknown>;
  };
  if (dropOverloads && dropOverloads.length > 0) {
    body.policy = { ...(body.policy ?? {}), dropOverloads };
  }
  // Re-attached after pruning: a property is identified by its key, so an entry whose value is empty is a
  // value the user chose, not an empty field to drop.
  data.localityLbEndpoints.forEach((entry, index) => {
    const additionalProperties = entry.watcher.additionalProperties;
    const target = body.localityLbEndpoints?.[index];
    if (target && additionalProperties && Object.keys(additionalProperties).length > 0) {
      target.watcher = { ...(target.watcher ?? {}), additionalProperties };
    }
  });
  return jsYaml.dump(body);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function parseToFormData(aggregatorId: string, raw: any): FormData {
  // The content API returns YAML files as a raw string; parse it to an object before extracting fields.
  // Throws YAMLException if raw is a string that is not valid YAML; callers must catch and notify the user.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const content: any = typeof raw === 'string' ? jsYaml.load(raw) : raw;
  const entries = Array.isArray(content?.localityLbEndpoints) ? content.localityLbEndpoints : [];
  const localityLbEndpoints: LocalityLbEndpointsForm[] = entries.map(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (entry: any) => ({
      ...entry,
      locality: entry?.locality ?? {},
      watcher: {
        ...entry?.watcher,
        kubeconfig: entry?.watcher?.kubeconfig ?? {},
        metadataMapping: (Array.isArray(entry?.watcher?.metadataMapping)
          ? entry.watcher.metadataMapping
          : []
        ).map(
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (rule: any) => rule,
        ),
      },
    }),
  );
  return {
    aggregatorId,
    localityLbEndpoints: localityLbEndpoints.length > 0 ? localityLbEndpoints : [{ ...emptyWatcher }],
    policy: { ...emptyPolicy, ...content?.policy },
  };
}

interface CredentialOption extends OptionBase {
  value: string;
  label: string;
}

// One metadata mapping. The schema stores either sourceKey or sourceKeyPrefix, so the row offers one input
// and a selector; the selector is local state and switching it clears the field it turns off, which keeps the
// form value equal to the document.
const MappingRow = ({
  watcherIndex,
  mappingIndex,
  defaultValue,
  register,
  setValue,
  getValues,
  readOnly,
  onRemove,
}: {
  watcherIndex: number;
  mappingIndex: number;
  defaultValue: MappingForm;
  register: UseFormRegister<FormData>;
  setValue: UseFormSetValue<FormData>;
  getValues: UseFormGetValues<FormData>;
  readOnly: boolean;
  onRemove: () => void;
}) => {
  const [prefixMode, setPrefixMode] = useState(defaultValue.sourceKeyPrefix != null);
  const path = `localityLbEndpoints.${watcherIndex}.watcher.metadataMapping.${mappingIndex}` as const;
  return (
    <Box borderWidth="1px" borderRadius="md" p={3} mb={2}>
      <Flex align="center" mb={2}>
        <Text fontSize="sm" fontWeight="semibold">
          Mapping #{mappingIndex + 1}
        </Text>
        <Spacer />
        {!readOnly && (
          <IconButton
            size="xs"
            variant="ghost"
            colorScheme="red"
            aria-label="Remove mapping"
            icon={<AiOutlineDelete />}
            onClick={onRemove}
          />
        )}
      </Flex>
      <SimpleGrid columns={2} spacingX={4} spacingY={4}>
        <FormControl>
          <Label>Read from</Label>
          <ChakraSelect
            size="sm"
            isDisabled={readOnly}
            _disabled={{ opacity: 1, cursor: 'default' }}
            {...register(`${path}.resourceType`)}
          >
            <option value="NODE">Node</option>
            <option value="POD">Pod</option>
          </ChakraSelect>
          <Help>Read the value from the Pod or its Node.</Help>
        </FormControl>
        <FormControl>
          <Label>Entry type</Label>
          <ChakraSelect
            size="sm"
            isDisabled={readOnly}
            _disabled={{ opacity: 1, cursor: 'default' }}
            {...register(`${path}.entryType`)}
          >
            <option value="LABEL">Label</option>
            <option value="ANNOTATION">Annotation</option>
          </ChakraSelect>
          <Help>Read it from a label or an annotation.</Help>
        </FormControl>
        <FormControl>
          <Label>Match</Label>
          <ChakraSelect
            size="sm"
            isDisabled={readOnly}
            _disabled={{ opacity: 1, cursor: 'default' }}
            value={prefixMode ? 'prefix' : 'key'}
            onChange={(e) => {
              const prefix = e.target.value === 'prefix';
              setPrefixMode(prefix);
              // The document stores one key or the other, so move what was typed instead of dropping it.
              if (prefix) {
                setValue(`${path}.sourceKeyPrefix`, getValues(`${path}.sourceKey`) ?? '');
                setValue(`${path}.sourceKey`, '');
                // The server keeps the source keys in prefix mode and ignores this one.
                setValue(`${path}.metadataKey`, '');
              } else {
                setValue(`${path}.sourceKey`, getValues(`${path}.sourceKeyPrefix`) ?? '');
                setValue(`${path}.sourceKeyPrefix`, '');
              }
            }}
          >
            <option value="key">Exact key</option>
            <option value="prefix">Key prefix</option>
          </ChakraSelect>
          <Help>Copy one key, or every key with a prefix.</Help>
        </FormControl>
        <FormControl>
          <Label>{prefixMode ? 'Source key prefix' : 'Source key'}</Label>
          <Input
            size="sm"
            placeholder={prefixMode ? 'topology.kubernetes.io/' : 'topology.kubernetes.io/zone'}
            isReadOnly={readOnly}
            {...register(prefixMode ? `${path}.sourceKeyPrefix` : `${path}.sourceKey`)}
          />
          <Help>{prefixMode ? 'Copy every key starting with this.' : 'The key to copy the value from.'}</Help>
        </FormControl>
        <FormControl>
          <Label>Metadata namespace</Label>
          <Input
            size="sm"
            placeholder="envoy.lb"
            isReadOnly={readOnly}
            {...register(`${path}.metadataNamespace`)}
          />
          <Help>Stored under this namespace. Defaults to envoy.lb.</Help>
        </FormControl>
        <FormControl>
          <Label>Metadata key</Label>
          <Input
            size="sm"
            placeholder={prefixMode ? 'keeps the source keys' : 'defaults to the source key'}
            isReadOnly={readOnly}
            isDisabled={prefixMode}
            {...register(`${path}.metadataKey`)}
          />
          <Help>
            {prefixMode
              ? 'Unused — the source keys are kept.'
              : 'Stored under this key. Defaults to the source key.'}
          </Help>
        </FormControl>
      </SimpleGrid>
    </Box>
  );
};

interface WatcherFieldsProps {
  index: number;
  control: Control<FormData>;
  register: UseFormRegister<FormData>;
  serviceNameError: boolean;
  controlPlaneUrlError: boolean;
  setValue: UseFormSetValue<FormData>;
  getValues: UseFormGetValues<FormData>;
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
  setValue,
  getValues,
  credentialOptions,
  onRemove,
  canRemove,
  readOnly,
}: WatcherFieldsProps) => {
  const mappings = useFieldArray({
    control,
    name: `localityLbEndpoints.${index}.watcher.metadataMapping`,
  });
  return (
    <Box borderWidth="1px" borderRadius="md" p={4} mb={4} maxW="3xl">
      <Flex mb={2} align="center">
        <Text fontWeight="bold">Kubernetes endpoint source #{index + 1}</Text>
        <Spacer />
        {canRemove && !readOnly && (
          <Button size="xs" variant="ghost" colorScheme="red" leftIcon={<AiOutlineDelete />} onClick={onRemove}>
            Remove
          </Button>
        )}
      </Flex>
      <SectionLabel mt={0}>Cluster access</SectionLabel>
      <SimpleGrid columns={2} spacingX={4} spacingY={4}>
        <FormControl isInvalid={controlPlaneUrlError} isRequired>
          <Label>Control plane URL</Label>
          <Input
            size="sm"
            placeholder="https://kubernetes.default.svc"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.kubeconfig.controlPlaneUrl`, {
              required: true,
            })}
          />
          <FormErrorMessage>Control plane URL is required.</FormErrorMessage>
          <Help>The Kubernetes API server to read from.</Help>
        </FormControl>
        <FormControl>
          <Label>Namespace</Label>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.kubeconfig.namespace`)}
          />
          <Help>Defaults to the credential&apos;s namespace.</Help>
        </FormControl>
        <FormControl>
          <Label>Credential ID</Label>
          {credentialOptions !== null ? (
            <Controller
              control={control}
              name={`localityLbEndpoints.${index}.watcher.kubeconfig.credentialId`}
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
              {...register(`localityLbEndpoints.${index}.watcher.kubeconfig.credentialId`)}
            />
          )}
          <Help>Empty if the cluster needs none.</Help>
        </FormControl>
        <FormControl pt={6}>
          <Checkbox
            size="sm"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.kubeconfig.trustCerts`)}
          >
            Trust certificates
          </Checkbox>
          <Help ml={6}>Skips TLS verification. Only for a self-signed control plane.</Help>
        </FormControl>
      </SimpleGrid>

      <SectionLabel>Endpoints</SectionLabel>
      <SimpleGrid columns={2} spacingX={4} spacingY={4}>
        <FormControl isInvalid={serviceNameError} isRequired>
          <Label>Service name</Label>
          <Input
            size="sm"
            placeholder="k8s service name"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.serviceName`, { required: true })}
          />
          <FormErrorMessage>Service name is required.</FormErrorMessage>
          <Help>Its Pods become the endpoints.</Help>
        </FormControl>
        <FormControl>
          <Label>Port name</Label>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.portName`)}
          />
          <Help>Only when the Service has several ports.</Help>
        </FormControl>
        <FormControl>
          <Label>Priority</Label>
          <Input
            size="sm"
            type="number"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.priority`, { valueAsNumber: true })}
          />
          <Help>0 is highest; the next takes over.</Help>
        </FormControl>
        <FormControl>
          <Label>Load balancing weight</Label>
          <Input
            size="sm"
            type="number"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.loadBalancingWeight`, { valueAsNumber: true })}
          />
          <Help>Share of traffic relative to the other sources.</Help>
        </FormControl>
        <FormControl gridColumn="1 / -1">
          <Checkbox
            size="sm"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.watcher.distinctEndpoint`)}
          >
            Distinct endpoint
          </Checkbox>
          <Help ml={6}>Collapses endpoints sharing a host and port.</Help>
        </FormControl>
      </SimpleGrid>

      <SectionLabel>Locality (optional)</SectionLabel>
      <Text mb={3} fontSize="xs" color="gray.500">
        Reported to Envoy so it can prefer endpoints close to the caller.
      </Text>
      <SimpleGrid columns={3} spacingX={4} spacingY={4}>
        <FormControl>
          <Label>Region</Label>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.locality.region`)}
          />
        </FormControl>
        <FormControl>
          <Label>Zone</Label>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.locality.zone`)}
          />
        </FormControl>
        <FormControl>
          <Label>Sub zone</Label>
          <Input
            size="sm"
            placeholder="optional"
            isReadOnly={readOnly}
            {...register(`localityLbEndpoints.${index}.locality.subZone`)}
          />
        </FormControl>
      </SimpleGrid>

      <SectionLabel>Additional properties (optional)</SectionLabel>
      <Text mb={2} fontSize="xs" color="gray.500">
        Passed to the server-side resolvers.
      </Text>
      <Controller
        control={control}
        name={`localityLbEndpoints.${index}.watcher.additionalProperties`}
        render={({ field }) => (
          <KeyValueEditor value={field.value} onChange={field.onChange} readOnly={readOnly} />
        )}
      />

      <SectionLabel>Metadata mappings (optional)</SectionLabel>
      <Text mb={2} fontSize="xs" color="gray.500">
        Copies Pod or Node labels and annotations into the endpoint metadata for routing rules to match on.
      </Text>
      {mappings.fields.map((field, mappingIndex) => (
        <MappingRow
          key={field.id}
          watcherIndex={index}
          mappingIndex={mappingIndex}
          defaultValue={field}
          register={register}
          setValue={setValue}
          getValues={getValues}
          readOnly={readOnly}
          onRemove={() => mappings.remove(mappingIndex)}
        />
      ))}
      {!readOnly && (
        <Button
          size="xs"
          variant="outline"
          leftIcon={<IoAddCircleOutline />}
          onClick={() => mappings.append({ ...emptyMapping })}
        >
          Add mapping
        </Button>
      )}
    </Box>
  );
};

interface AggregatorFormFieldsProps {
  group: string;
  control: Control<FormData>;
  register: UseFormRegister<FormData>;
  setValue: UseFormSetValue<FormData>;
  getValues: UseFormGetValues<FormData>;
  errors: FieldErrors<FormData>;
  idReadOnly: boolean;
  readOnly: boolean;
}

const AggregatorFormFields = ({
  group,
  control,
  register,
  setValue,
  getValues,
  errors,
  idReadOnly,
  readOnly,
}: AggregatorFormFieldsProps) => {
  const { fields, append, remove } = useFieldArray({ control, name: 'localityLbEndpoints' });
  // Offer the group's access-token credentials as a dropdown. Listing requires the ADMIN role, so on any error
  // (e.g. 403 for non-admins) fall back to a free-text credential id input.
  const { data: credentials, error: credentialsError } = useListCredentialsQuery({ group });
  const credentialOptions: string[] | null = credentialsError
    ? null
    : (credentials || []).filter((c) => c.type === 'ACCESS_TOKEN').map((c) => c.id);
  return (
    <>
      <FormControl isInvalid={!!errors.aggregatorId} isRequired mb={4} maxW="md">
        <Label fontSize="md">Aggregator ID</Label>
        <Input
          placeholder="e.g. my-service"
          isReadOnly={idReadOnly || readOnly}
          {...register('aggregatorId', {
            required: true,
            // Skip the pattern check for existing resources: the ID is immutable and may contain
            // slashes that were allowed before this validation was introduced.
            pattern: idReadOnly ? undefined : AGGREGATOR_ID_PATTERN,
          })}
        />
        <FormErrorMessage>
          ID must match [a-z](?:[a-z0-9_.-]*[a-z0-9])? (dots allowed, slashes not allowed)
        </FormErrorMessage>
        <Help>Names the aggregator and its cluster.</Help>
      </FormControl>

      {fields.map((field, index) => (
        <WatcherFields
          key={field.id}
          index={index}
          control={control}
          register={register}
          setValue={setValue}
          getValues={getValues}
          serviceNameError={!!errors.localityLbEndpoints?.[index]?.watcher?.serviceName}
          controlPlaneUrlError={!!errors.localityLbEndpoints?.[index]?.watcher?.kubeconfig?.controlPlaneUrl}
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
          Add source
        </Button>
      )}

      <PolicyFields control={control} register={register} readOnly={readOnly} />
      {/* Room for the sticky action bar, which would otherwise cover the last fields. */}
      <Box h={16} />
    </>
  );
};

// A drop percentage is a numerator over a chosen denominator; render it the way an operator reads it.
function formatDropShare(drop: DropOverload): string {
  const numerator = drop.dropPercentage?.numerator ?? 0;
  const denominator = drop.dropPercentage?.denominator ?? 'HUNDRED';
  if (denominator === 'HUNDRED') {
    return `${numerator}%`;
  }
  return `${numerator} in ${(denominator === 'MILLION' ? 1_000_000 : 10_000).toLocaleString('en-US')}`;
}

// Marks a policy field the Armeria xDS client does not read, so an operator does not expect an effect.
const EnvoyOnlyBadge = () => (
  <Tooltip label="The Armeria xDS client ignores this field; only Envoy applies it.">
    <Badge ml={2} colorScheme="orange" cursor="help">
      Envoy only
    </Badge>
  </Tooltip>
);

// Load-balancing policy of the generated ClusterLoadAssignment. Envoy honours every field the form offers;
// the Armeria xDS client reads only the first two, so the rest is marked to set the operator's expectation.
const PolicyFields = ({
  control,
  register,
  readOnly,
}: {
  control: Control<FormData>;
  register: UseFormRegister<FormData>;
  readOnly: boolean;
}) => {
  const dropOverloads = useWatch({ control, name: 'policy.dropOverloads' }) as DropOverload[] | undefined;
  return (
    <Box borderWidth="1px" borderRadius="md" p={4} mt={4} maxW="3xl">
      <Text fontWeight="semibold" mb={3}>
        Policy (optional)
      </Text>
      <Stack spacing={4}>
        <FormControl>
          <Label>Overprovisioning factor</Label>
          <Input
            size="sm"
            maxW="xs"
            type="number"
            placeholder="140 (default)"
            isReadOnly={readOnly}
            {...register('policy.overprovisioningFactor', { valueAsNumber: true })}
          />
          <Help>Healthy above 100/factor — 140 means 72%.</Help>
        </FormControl>
        <FormControl>
          <Checkbox size="sm" isReadOnly={readOnly} {...register('policy.weightedPriorityHealth')}>
            Weighted priority health
          </Checkbox>
          <Help>Weighs priority health by endpoint weight, not count.</Help>
        </FormControl>
        <FormControl>
          <Label>
            Endpoint stale after
            <EnvoyOnlyBadge />
          </Label>
          <Input
            size="sm"
            maxW="xs"
            placeholder="e.g. 30s"
            isReadOnly={readOnly}
            {...register('policy.endpointStaleAfter')}
          />
          <Help>Drops an endpoint unrefreshed for this long.</Help>
        </FormControl>
      </Stack>
      {dropOverloads && dropOverloads.length > 0 && (
        <Box mt={4}>
          <Text fontSize="sm" fontWeight="semibold">
            Drop overload
            <EnvoyOnlyBadge />
          </Text>
          <Text fontSize="xs" color="gray.500" mb={2}>
            Envoy drops this share of requests to the cluster. Edit it where it was set.
          </Text>
          {dropOverloads.map((drop, dropIndex) => (
            <Text key={dropIndex} fontSize="sm">
              {drop.category ?? '(no category)'} — {formatDropShare(drop)}
            </Text>
          ))}
        </Box>
      )}
    </Box>
  );
};

const NewK8sAggregatorEditor = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  // Creating an aggregator requires WRITE on the group, mirroring the Edit/Delete gating in
  // ExistingK8sAggregatorEditor.
  const { hasWrite, isLoading: accessLoading } = useGroupWriteAccess(group);
  const [createAggregator, { isLoading }] = useCreateK8sAggregatorMutation();
  const [previewAggregator, { isLoading: isPreviewing }] = usePreviewK8sAggregatorMutation();
  const { isOpen: previewOpen, onOpen: openPreview, onClose: closePreview } = useDisclosure();
  const [previewResult, setPreviewResult] = useState<K8sPreviewResult | null>(null);
  const [commitSummary, setCommitSummary] = useState('');
  const {
    register,
    control,
    setValue,
    getValues,
    handleSubmit,
    formState: { errors },
  } = useForm<FormData>({
    defaultValues: { aggregatorId: '', localityLbEndpoints: [{ ...emptyWatcher }], policy: { ...emptyPolicy } },
  });

  const onPreview = async (data: FormData) => {
    setPreviewResult(null);
    openPreview();
    try {
      const yamlText = await previewAggregator({ group, body: buildBody(data) }).unwrap();
      setPreviewResult({ ok: true, assignment: jsYaml.load(yamlText as string) });
    } catch (err) {
      setPreviewResult({ ok: false, error: ErrorMessageParser.parse(err) });
    }
  };

  const onSubmit = async (data: FormData) => {
    if (!hasWrite) {
      return;
    }
    try {
      await createAggregator({
        group,
        aggregatorId: data.aggregatorId,
        body: buildBody(data),
        summary: commitSummary || undefined,
      }).unwrap();
      dispatch(
        newNotification('Aggregator created', `Aggregator '${data.aggregatorId}' is created`, 'success'),
      );
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`);
    } catch (err) {
      dispatch(newNotification('Failed to create the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  if (accessLoading) {
    return null;
  }
  if (!hasWrite) {
    return (
      <Alert status="warning" borderRadius="md">
        <AlertIcon />
        You need the WRITE role on this group to create an aggregator.
      </Alert>
    );
  }

  return (
    <Box>
      <AggregatorFormFields
        group={group}
        control={control}
        register={register}
        setValue={setValue}
        getValues={getValues}
        errors={errors}
        idReadOnly={false}
        readOnly={false}
      />
      <EditorActionBar
        maxW="3xl"
        commitSummary={commitSummary}
        onCommitSummaryChange={setCommitSummary}
        commitPlaceholder="Create kubernetes endpoint: ..."
      >
        <Button
          variant="outline"
          colorScheme="teal"
          leftIcon={<AiOutlineEye />}
          onClick={handleSubmit(onPreview)}
          isLoading={isPreviewing}
        >
          Preview endpoints
        </Button>
        <Button colorScheme="teal" leftIcon={<FiSave />} onClick={handleSubmit(onSubmit)} isLoading={isLoading}>
          Create
        </Button>
      </EditorActionBar>
      <K8sAggregatorPreviewModal
        isOpen={previewOpen}
        onClose={closePreview}
        isLoading={isPreviewing}
        result={previewResult}
      />
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
  const [previewAggregator, { isLoading: isPreviewing }] = usePreviewK8sAggregatorMutation();
  const { isOpen, onOpen, onClose } = useDisclosure();
  const { isOpen: previewOpen, onOpen: openPreview, onClose: closePreview } = useDisclosure();
  const [previewResult, setPreviewResult] = useState<K8sPreviewResult | null>(null);
  // An aggregator opens in read-only view; the user must click Edit to modify it (like the resource editors).
  const [editing, setEditing] = useState(false);
  const [commitSummary, setCommitSummary] = useState('');
  const [deleteCommitSummary, setDeleteCommitSummary] = useState('');
  const {
    register,
    control,
    setValue,
    getValues,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    defaultValues: { aggregatorId: id, localityLbEndpoints: [{ ...emptyWatcher }], policy: { ...emptyPolicy } },
  });

  // Sync the form to the latest fetched content, but never while editing so a background refetch cannot
  // clobber unsaved edits.
  useEffect(() => {
    if (data && !editing) {
      try {
        const file = data as FileContentDto;
        reset({ ...parseToFormData(id, file.content), loadedRevision: String(file.revision) });
      } catch (e) {
        dispatch(newNotification('Failed to load aggregator', (e as Error).message, 'error'));
      }
    }
  }, [data, id, reset, editing, dispatch]);

  const onSubmit = async (formData: FormData) => {
    const name = `groups/${group}/k8s/endpointAggregators/${id}`;
    try {
      await updateAggregator({
        group,
        id,
        body: buildBody(formData, name),
        summary: commitSummary || undefined,
        revision: String(formData.loadedRevision),
      }).unwrap();
      dispatch(newNotification('Aggregator updated', `Aggregator '${id}' is updated`, 'success'));
      setEditing(false);
      setCommitSummary('');
    } catch (err) {
      if ((err as FetchBaseQueryError | undefined)?.status === 409) {
        dispatch(
          newNotification(
            'Update conflict',
            `Group '${group}' changed after you loaded this aggregator. Reload the page and re-apply your edits.`,
            'error',
          ),
        );
      } else {
        dispatch(newNotification('Failed to update the aggregator', ErrorMessageParser.parse(err), 'error'));
      }
    }
  };

  const handleCancel = () => {
    if (data) {
      try {
        const file = data as FileContentDto;
        reset({ ...parseToFormData(id, file.content), loadedRevision: String(file.revision) });
      } catch (e) {
        dispatch(newNotification('Failed to restore aggregator content', (e as Error).message, 'error'));
      }
    }
    setEditing(false);
    setCommitSummary('');
  };

  const onPreview = async (formData: FormData) => {
    setPreviewResult(null);
    openPreview();
    try {
      const yamlText = await previewAggregator({ group, body: buildBody(formData) }).unwrap();
      setPreviewResult({ ok: true, assignment: jsYaml.load(yamlText as string) });
    } catch (err) {
      setPreviewResult({ ok: false, error: ErrorMessageParser.parse(err) });
    }
  };

  const handleDelete = async () => {
    try {
      await deleteAggregator({ group, id, summary: deleteCommitSummary || undefined }).unwrap();
      dispatch(newNotification('Aggregator deleted', `Aggregator '${id}' is deleted`, 'success'));
      Router.push(`/app/xds/group?name=${encodeURIComponent(group)}&type=k8sAggregators`);
    } catch (err) {
      dispatch(newNotification('Failed to delete the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  const handleDeleteModalClose = () => {
    setDeleteCommitSummary('');
    onClose();
  };

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box>
          {/* Read-mode actions; while editing, Cancel/Preview/Save live in the sticky action bar. */}
          {hasWrite && !editing && (
            <Flex mb={2} maxW="3xl" align="center">
              <Spacer />
              <HStack spacing={3}>
                <Button
                  variant="outline"
                  colorScheme="teal"
                  leftIcon={<AiOutlineEye />}
                  size="sm"
                  onClick={handleSubmit(onPreview)}
                  isLoading={isPreviewing}
                >
                  Preview
                </Button>
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
            </Flex>
          )}
          <K8sAggregatorStatus group={group} id={id} />
          <AggregatorFormFields
            group={group}
            control={control}
            register={register}
            setValue={setValue}
            getValues={getValues}
            errors={errors}
            idReadOnly
            readOnly={!editing}
          />
          {editing && hasWrite && (
            <EditorActionBar
              maxW="3xl"
              commitSummary={commitSummary}
              onCommitSummaryChange={setCommitSummary}
              commitPlaceholder="Update kubernetes endpoint aggregator: ..."
            >
              <Button variant="outline" colorScheme="gray" leftIcon={<AiOutlineClose />} onClick={handleCancel}>
                Cancel
              </Button>
              <Button
                variant="outline"
                colorScheme="teal"
                leftIcon={<AiOutlineEye />}
                onClick={handleSubmit(onPreview)}
                isLoading={isPreviewing}
              >
                Preview endpoints
              </Button>
              <Button
                colorScheme="teal"
                leftIcon={<FiSave />}
                onClick={handleSubmit(onSubmit)}
                isLoading={isSaving}
              >
                Save
              </Button>
            </EditorActionBar>
          )}
          <K8sAggregatorPreviewModal
            isOpen={previewOpen}
            onClose={closePreview}
            isLoading={isPreviewing}
            result={previewResult}
          />
          <DeleteConfirmationModal
            isOpen={isOpen}
            onClose={handleDeleteModalClose}
            type="aggregator"
            id={id}
            from={group}
            handleDelete={handleDelete}
            isLoading={isDeleting}
          >
            <FormControl mt={4}>
              <FormLabel>Commit summary</FormLabel>
              <Input
                value={deleteCommitSummary}
                onChange={(e) => setDeleteCommitSummary(e.target.value)}
                placeholder="Delete kubernetes endpoint aggregator: ..."
              />
            </FormControl>
          </DeleteConfirmationModal>
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
        <ExistingK8sAggregatorEditor key={id} group={group} id={id} />
      )}
    </Box>
  );
};
