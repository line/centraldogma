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
  Badge,
  Box,
  Flex,
  FormControl,
  FormLabel,
  HStack,
  Select,
  Tag,
  Text,
  Tooltip,
  Wrap,
} from '@chakra-ui/react';
import { useMemo, useState } from 'react';
import { Deferred } from 'dogma/common/components/Deferred';
import { JsonEditor } from 'dogma/common/components/JsonEditor';
import { useGetReposQuery, useGetXdsAppsQuery, useGetXdsSnapshotQuery } from 'dogma/features/api/apiSlice';
import { XdsSnapshotType } from 'dogma/features/xds/ControlPlaneStatusDto';
import {
  XDS_PROJECT,
  XDS_RESOURCE_META,
  XDS_RESOURCE_TYPES,
  XdsResourceType,
} from 'dogma/features/xds/XdsTypes';

// Repositories created automatically for every project; they are not xDS groups.
const INTERNAL_REPOS = new Set(['dogma', 'meta']);

const ALL_GROUPS = '';

type Scope = 'group' | 'app';

function prettyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

export const SnapshotViewer = () => {
  const { data: repos } = useGetReposQuery(XDS_PROJECT);
  const groups = useMemo(
    () => (repos || []).map((repo) => repo.name).filter((name) => !INTERNAL_REPOS.has(name)),
    [repos],
  );
  const { data: apps } = useGetXdsAppsQuery();

  const [scope, setScope] = useState<Scope>('group');
  const [group, setGroup] = useState<string>(ALL_GROUPS);
  const [appId, setAppId] = useState<string>('');
  const [type, setType] = useState<XdsResourceType>('listeners');
  const [resource, setResource] = useState<string>('');

  // Keep the selected app id valid as the app list loads; default to the first one.
  const appIds = useMemo(() => (apps || []).map((a) => a.appId), [apps]);
  const selectedAppId = appId && appIds.includes(appId) ? appId : appIds[0] || '';

  const {
    data: snapshot,
    isFetching,
    error,
  } = useGetXdsSnapshotQuery(
    scope === 'app' ? { appId: selectedAppId || undefined } : { group: group || undefined },
    { refetchOnMountOrArgChange: true },
  );

  const typeSnapshot: XdsSnapshotType | undefined = snapshot?.[type];
  const resourceNames = useMemo(
    () => (typeSnapshot ? Object.keys(typeSnapshot.resources).sort() : []),
    [typeSnapshot],
  );

  // Keep the selected resource valid as the type/scope changes; default to the first one.
  const selectedResource = resource && resourceNames.includes(resource) ? resource : resourceNames[0] || '';
  const servedJson = selectedResource ? prettyJson(typeSnapshot?.resources[selectedResource]) : '';

  // An app id that has never connected has no cached snapshot, so nothing is served to it.
  const notServed = scope === 'app' && selectedAppId !== '' && snapshot?.served === false;

  return (
    <Box>
      <Flex gap={4} mb={4} wrap="wrap" align="flex-end">
        <FormControl maxW="2xs">
          <FormLabel fontSize="sm">Scope</FormLabel>
          <Select value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
            <option value="group">By group</option>
            <option value="app">By application identity</option>
          </Select>
        </FormControl>
        {scope === 'group' ? (
          <FormControl maxW="2xs">
            <FormLabel fontSize="sm">Group</FormLabel>
            <Select value={group} onChange={(e) => setGroup(e.target.value)}>
              <option value={ALL_GROUPS}>All groups (default)</option>
              {groups.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </Select>
          </FormControl>
        ) : (
          <FormControl maxW="2xs">
            <FormLabel fontSize="sm">Application identity</FormLabel>
            <Select
              value={selectedAppId}
              onChange={(e) => setAppId(e.target.value)}
              isDisabled={appIds.length === 0}
            >
              {appIds.length === 0 && <option value="">(no app has connected)</option>}
              {appIds.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </Select>
          </FormControl>
        )}
        <FormControl maxW="2xs">
          <FormLabel fontSize="sm">Type</FormLabel>
          <Select value={type} onChange={(e) => setType(e.target.value as XdsResourceType)}>
            {XDS_RESOURCE_TYPES.map((t) => (
              <option key={t} value={t}>
                {XDS_RESOURCE_META[t].acronym} · {XDS_RESOURCE_META[t].label}
              </option>
            ))}
          </Select>
        </FormControl>
        <FormControl maxW="md">
          <FormLabel fontSize="sm">Resource</FormLabel>
          <Select
            value={selectedResource}
            onChange={(e) => setResource(e.target.value)}
            isDisabled={resourceNames.length === 0}
          >
            {resourceNames.length === 0 && <option value="">(none)</option>}
            {resourceNames.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </Select>
        </FormControl>
      </Flex>

      <Deferred isLoading={isFetching} error={error}>
        {() => (
          <Box>
            {scope === 'app' && selectedAppId !== '' && snapshot?.readableGroups && (
              <HStack mb={3} spacing={2} align="center">
                <Text fontSize="sm" fontWeight="bold">
                  Readable groups:
                </Text>
                {snapshot.readableGroups.length === 0 ? (
                  <Text fontSize="sm" color="gray.500">
                    none
                  </Text>
                ) : (
                  <Wrap>
                    {snapshot.readableGroups.map((g) => (
                      <Tag key={g} size="sm" colorScheme="purple">
                        {g}
                      </Tag>
                    ))}
                  </Wrap>
                )}
              </HStack>
            )}

            {notServed ? (
              <Alert status="info" borderRadius="md" fontSize="sm">
                <AlertIcon />
                This application identity has never connected, so no snapshot is being served to it.
              </Alert>
            ) : (
              <>
                <HStack mb={3} spacing={4}>
                  <HStack>
                    <Text fontSize="sm" fontWeight="bold">
                      Version (all resources):
                    </Text>
                    <Tooltip
                      label={
                        `SHA-256 over all ${XDS_RESOURCE_META[type].label.toLowerCase()}s in this scope — ` +
                        'the version a wildcard subscriber ACKs. A client that subscribes to specific ' +
                        'resources (e.g. EDS/RDS by name) ACKs a different, subset version, so it is not ' +
                        'directly comparable with a connected client’s acked version.'
                      }
                    >
                      <Badge colorScheme="teal">{typeSnapshot?.version || '(empty)'}</Badge>
                    </Tooltip>
                  </HStack>
                  <Text fontSize="sm" color="gray.500">
                    {resourceNames.length} {XDS_RESOURCE_META[type].label.toLowerCase()}(s) served
                  </Text>
                </HStack>

                {resourceNames.length === 0 ? (
                  <Text color="gray.500">
                    No {XDS_RESOURCE_META[type].label.toLowerCase()}s are being served.
                  </Text>
                ) : (
                  <JsonEditor value={servedJson} readOnly />
                )}
              </>
            )}
          </Box>
        )}
      </Deferred>
    </Box>
  );
};
