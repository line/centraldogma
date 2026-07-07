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
import { Alert, AlertIcon, Badge, Box, Heading, HStack, Link, Spinner, Text } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { useMemo } from 'react';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useGetGroupHistoryQuery, useGetResourceQuery } from 'dogma/features/xds/xdsApiSlice';

// The aggregator writes its generated endpoints to this path; reading it tells us whether (and when) the
// aggregator has produced endpoints from Kubernetes.
const generatedPath = (id: string) => `/k8s/endpoints/${id}.yaml`;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function countEndpoints(content: any): { localities: number; endpoints: number } {
  const localities = Array.isArray(content?.endpoints) ? content.endpoints : [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const endpoints = localities.reduce(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (sum: number, locality: any) =>
      sum + (Array.isArray(locality?.lbEndpoints) ? locality.lbEndpoints.length : 0),
    0,
  );
  return { localities: localities.length, endpoints };
}

// Shows whether the aggregator has synced endpoints from Kubernetes, derived from the generated EDS file it
// writes to the repository: its presence/age (last commit) and the number of endpoints it contains. Failures
// to reach Kubernetes are not surfaced here (they are only logged by the control plane).
export const K8sAggregatorStatus = ({ group, id }: { group: string; id: string }) => {
  const { data, error, isFetching } = useGetResourceQuery(
    { group, type: 'endpoints', id, k8s: true },
    { refetchOnMountOrArgChange: true },
  );
  const { data: history } = useGetGroupHistoryQuery({ group, filePath: generatedPath(id), maxCommits: 1 });
  // Pre-migration servers store the endpoint file under .json; fall back to that path if .yaml has
  // no history yet (i.e. the aggregator has not yet pushed after the YAML migration was deployed).
  const { data: legacyHistory } = useGetGroupHistoryQuery(
    { group, filePath: `/k8s/endpoints/${id}.json`, maxCommits: 1 },
    { skip: !history || history.length > 0 },
  );

  const notSynced = (error as FetchBaseQueryError | undefined)?.status === 404;
  const { localities, endpoints } = useMemo(() => countEndpoints(data?.content), [data]);
  const lastCommit = history?.[0] ?? legacyHistory?.[0];
  const resourceHref =
    `/app/xds/resource?group=${encodeURIComponent(group)}&type=endpoints` +
    `&id=${encodeURIComponent(id)}&k8s=true`;

  return (
    <Box borderWidth="1px" borderRadius="md" p={4} mb={4} maxW="3xl">
      <Heading size="sm" mb={3}>
        Sync status
      </Heading>
      {isFetching ? (
        <Spinner size="sm" />
      ) : notSynced ? (
        <Alert status="info" borderRadius="md" fontSize="sm">
          <AlertIcon />
          No endpoints generated yet. The aggregator may still be connecting to Kubernetes, or no matching
          endpoints exist.
        </Alert>
      ) : error ? (
        <Alert status="error" borderRadius="md" fontSize="sm">
          <AlertIcon />
          Could not load sync status.
        </Alert>
      ) : (
        <HStack spacing={4} wrap="wrap" fontSize="sm">
          <Badge colorScheme="green">Synced</Badge>
          <Text>
            {localities} localit{localities === 1 ? 'y' : 'ies'} · {endpoints} endpoint
            {endpoints === 1 ? '' : 's'}
          </Text>
          {lastCommit && (
            <Text color="gray.600">
              Last synced <DateWithTooltip date={lastCommit.pushedAt} />
            </Text>
          )}
          <Link as={RouteLink} href={resourceHref} color="teal">
            View generated EDS →
          </Link>
        </HStack>
      )}
      <Text fontSize="xs" color="gray.500" mt={2}>
        Generated endpoints are written to <code>{generatedPath(id)}</code>.
      </Text>
    </Box>
  );
};
