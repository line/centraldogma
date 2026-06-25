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
  Badge,
  Box,
  Checkbox,
  HStack,
  Input,
  InputGroup,
  InputLeftElement,
  Link,
  Table,
  Tag,
  Tbody,
  Td,
  Text,
  Th,
  Thead,
  Tr,
  Wrap,
  WrapItem,
} from '@chakra-ui/react';
import { SearchIcon } from '@chakra-ui/icons';
import { default as RouteLink } from 'next/link';
import { useMemo, useState } from 'react';
import { Deferred } from 'dogma/common/components/Deferred';
import { useGetGroupGraphQuery } from 'dogma/features/xds/xdsApiSlice';
import { XdsGraphEdge, XdsRefStatus } from 'dogma/features/xds/xdsReferences';
import { XDS_RESOURCE_META, XdsResourceType } from 'dogma/features/xds/XdsTypes';

const nodeKey = (type: XdsResourceType, id: string) => `${type}/${id}`;

function resourceHref(group: string, type: XdsResourceType, id: string, k8s: boolean): string {
  const params = new URLSearchParams({ group, type, id });
  if (k8s) {
    params.set('k8s', 'true');
  }
  return `/app/xds/resource?${params.toString()}`;
}

function statusColor(status: XdsRefStatus): string {
  switch (status) {
    case 'ok':
      return 'green';
    case 'missing':
      return 'red';
    default:
      return 'gray';
  }
}

// A chip for an outgoing reference, linking to the referenced resource. Dangling (missing) references are red,
// cross-group (external) ones gray.
const OutgoingChip = ({ edge }: { edge: XdsGraphEdge }) => (
  <WrapItem>
    <Tag size="sm" colorScheme={statusColor(edge.status)} variant="subtle">
      <Link
        as={RouteLink}
        href={resourceHref(edge.targetGroup, edge.targetType, edge.targetId, edge.targetK8s)}
      >
        {XDS_RESOURCE_META[edge.targetType].acronym} · {edge.targetId}
        {edge.status === 'missing' && ' (missing)'}
        {edge.status === 'external' && ` @ ${edge.targetGroup}`}
      </Link>
    </Tag>
  </WrapItem>
);

export const ResourceReferences = ({ group }: { group: string }) => {
  const { data, isLoading, error } = useGetGroupGraphQuery({ group }, { refetchOnMountOrArgChange: true });
  const [query, setQuery] = useState('');
  const [onlyDangling, setOnlyDangling] = useState(false);

  const { rows, edgeCount, danglingCount } = useMemo(() => {
    const nodes = data?.nodes ?? [];
    const edges = data?.edges ?? [];
    const outgoing = new Map<string, XdsGraphEdge[]>();
    const incoming = new Map<string, XdsGraphEdge[]>();
    const push = (map: Map<string, XdsGraphEdge[]>, key: string, edge: XdsGraphEdge) => {
      const list = map.get(key);
      if (list) {
        list.push(edge);
      } else {
        map.set(key, [edge]);
      }
    };
    for (const edge of edges) {
      push(outgoing, nodeKey(edge.fromType, edge.fromId), edge);
      // External edges point outside this group, so they have no node here to be "referenced by".
      if (edge.status !== 'external') {
        push(incoming, nodeKey(edge.targetType, edge.targetId), edge);
      }
    }
    const rows = nodes.map((node) => {
      const key = nodeKey(node.type, node.id);
      return { node, out: outgoing.get(key) ?? [], in: incoming.get(key) ?? [] };
    });
    return {
      rows,
      edgeCount: edges.length,
      danglingCount: edges.filter((e) => e.status === 'missing').length,
    };
  }, [data]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows.filter((row) => {
      if (onlyDangling && !row.out.some((e) => e.status === 'missing')) {
        return false;
      }
      if (!q) {
        return true;
      }
      return row.node.name.toLowerCase().includes(q) || row.node.id.toLowerCase().includes(q);
    });
  }, [rows, query, onlyDangling]);

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() =>
        rows.length === 0 ? (
          <Text mt={4} color="gray.500">
            No resources in this group yet.
          </Text>
        ) : (
          <Box>
            <HStack mb={3} spacing={4} wrap="wrap">
              <InputGroup maxW="sm" size="sm">
                <InputLeftElement pointerEvents="none">
                  <SearchIcon color="gray.400" />
                </InputLeftElement>
                <Input
                  placeholder="Search resources…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  borderRadius="md"
                />
              </InputGroup>
              <Checkbox isChecked={onlyDangling} onChange={(e) => setOnlyDangling(e.target.checked)}>
                Only dangling references
              </Checkbox>
              <Text fontSize="sm" color="gray.500">
                {rows.length} resources · {edgeCount} references ·{' '}
                <Text as="span" color={danglingCount > 0 ? 'red.500' : 'gray.500'} fontWeight="semibold">
                  {danglingCount} dangling
                </Text>
              </Text>
            </HStack>

            <Table size="sm">
              <Thead>
                <Tr>
                  <Th>Resource</Th>
                  <Th>References (→)</Th>
                  <Th>Referenced by (←)</Th>
                </Tr>
              </Thead>
              <Tbody>
                {filtered.map((row) => (
                  <Tr key={nodeKey(row.node.type, row.node.id)}>
                    <Td>
                      <HStack spacing={2}>
                        <Tag size="sm" colorScheme="purple">
                          {XDS_RESOURCE_META[row.node.type].acronym}
                        </Tag>
                        <Link
                          as={RouteLink}
                          href={resourceHref(group, row.node.type, row.node.id, row.node.k8s)}
                          color="teal"
                          wordBreak="break-all"
                        >
                          {row.node.id}
                        </Link>
                      </HStack>
                    </Td>
                    <Td>
                      {row.out.length === 0 ? (
                        <Text color="gray.400">—</Text>
                      ) : (
                        <Wrap>
                          {row.out.map((edge, i) => (
                            <OutgoingChip key={`${edge.targetType}/${edge.targetId}/${i}`} edge={edge} />
                          ))}
                        </Wrap>
                      )}
                    </Td>
                    <Td>
                      {row.in.length === 0 ? (
                        <Text color="gray.400">—</Text>
                      ) : (
                        <Wrap>
                          {row.in.map((edge, i) => (
                            <WrapItem key={`${edge.fromType}/${edge.fromId}/${i}`}>
                              <Tag size="sm" colorScheme="blue" variant="subtle">
                                <Link
                                  as={RouteLink}
                                  href={resourceHref(group, edge.fromType, edge.fromId, false)}
                                >
                                  {XDS_RESOURCE_META[edge.fromType].acronym} · {edge.fromId}
                                </Link>
                              </Tag>
                            </WrapItem>
                          ))}
                        </Wrap>
                      )}
                    </Td>
                  </Tr>
                ))}
              </Tbody>
            </Table>
            {filtered.length === 0 && (
              <Text mt={4} color="gray.500">
                No resources match the current filter.
              </Text>
            )}
            <Box mt={4}>
              <Badge colorScheme="gray" variant="subtle" display="block" mb={1}>
                Cross-group references are shown as &quot;external&quot; and are not verified here.
              </Badge>
              <Badge colorScheme="gray" variant="subtle" display="block">
                &quot;Referenced by&quot; only covers resources within this group. References from other groups
                to resources here will not appear in the &larr; column.
              </Badge>
            </Box>
          </Box>
        )
      }
    </Deferred>
  );
};
