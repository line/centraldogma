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
  Button,
  Center,
  Flex,
  Link,
  Modal,
  ModalBody,
  ModalCloseButton,
  ModalContent,
  ModalHeader,
  ModalOverlay,
  Spinner,
  Tag,
  Text,
  useColorModeValue,
  useDisclosure,
} from '@chakra-ui/react';
import { FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { default as RouteLink } from 'next/link';
import { useEffect, useState } from 'react';
import { AiOutlinePartition } from 'react-icons/ai';
import { xdsApiSlice } from 'dogma/features/xds/xdsApiSlice';
import { XdsResourceType, XDS_RESOURCE_META, XDS_RESOURCE_TYPES } from 'dogma/features/xds/XdsTypes';
import { extractReferences, resolveReference } from 'dogma/features/xds/xdsReferences';
import { useAppDispatch } from 'dogma/hooks';

interface GraphNode {
  key: string;
  group: string;
  type: XdsResourceType;
  id: string;
  k8s: boolean;
  // The display label (the referenced resource name, or the resource name for the root).
  name: string;
  // 'missing' means the resource does not exist (404); 'error' means it could not be loaded (auth/server/
  // network failure), which is distinct from being absent.
  status: 'ok' | 'missing' | 'error';
}

interface GraphEdge {
  from: string;
  to: string;
}

interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

interface ResourceRef {
  group: string;
  type: XdsResourceType;
  id: string;
  k8s: boolean;
  name: string;
}

function nodeKey(group: string, type: XdsResourceType, id: string, k8s: boolean): string {
  return `${group}/${type}/${id}/${k8s ? 'k8s' : ''}`;
}

function resourceHref(node: GraphNode): string {
  const params = new URLSearchParams({ group: node.group, type: node.type, id: node.id });
  if (node.k8s) {
    params.set('k8s', 'true');
  }
  return `/app/xds/resource?${params.toString()}`;
}

// Walks the reference graph starting from the given resource, fetching each referenced resource's content and
// following its references (LDS -> RDS/CDS, RDS -> CDS, CDS -> EDS). Missing references (404) become 'missing'
// nodes; already-visited resources are not refetched, so cycles terminate.
async function buildGraph(dispatch: ReturnType<typeof useAppDispatch>, root: ResourceRef): Promise<Graph> {
  const nodes = new Map<string, GraphNode>();
  const edges: GraphEdge[] = [];
  const edgeKeys = new Set<string>();
  const visited = new Set<string>();
  const queue: ResourceRef[] = [root];
  nodes.set(nodeKey(root.group, root.type, root.id, root.k8s), {
    ...root,
    key: nodeKey(root.group, root.type, root.id, root.k8s),
    status: 'ok',
  });

  while (queue.length > 0) {
    const cur = queue.shift() as ResourceRef;
    const curKey = nodeKey(cur.group, cur.type, cur.id, cur.k8s);
    if (visited.has(curKey)) {
      continue;
    }
    visited.add(curKey);

    let content: string;
    const promise = dispatch(
      xdsApiSlice.endpoints.getResource.initiate({
        group: cur.group,
        type: cur.type,
        id: cur.id,
        k8s: cur.k8s,
      }),
    );
    try {
      const result = await promise.unwrap();
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const raw = (result as any).content;
      content = typeof raw === 'string' ? raw : JSON.stringify(raw ?? {});
      const node = nodes.get(curKey);
      if (node) {
        node.status = 'ok';
      }
    } catch (err) {
      const node = nodes.get(curKey);
      if (node) {
        // Only a 404 means the resource is absent; any other failure is a load error, not a missing node.
        node.status = (err as FetchBaseQueryError | undefined)?.status === 404 ? 'missing' : 'error';
      }
      continue;
    } finally {
      promise.unsubscribe?.();
    }

    // Endpoints (EDS) are leaves.
    if (cur.type === 'endpoints') {
      continue;
    }

    const children = extractReferences(cur.type, content).map((ref) => resolveReference(cur.group, ref));
    for (const child of children) {
      const childKey = nodeKey(child.group, child.targetType, child.id, child.k8s);
      if (!nodes.has(childKey)) {
        nodes.set(childKey, {
          key: childKey,
          group: child.group,
          type: child.targetType,
          id: child.id,
          k8s: child.k8s,
          name: child.name,
          status: 'ok',
        });
        queue.push({
          group: child.group,
          type: child.targetType,
          id: child.id,
          k8s: child.k8s,
          name: child.name,
        });
      }
      const edgeKey = `${curKey}->${childKey}`;
      if (!edgeKeys.has(edgeKey)) {
        edgeKeys.add(edgeKey);
        edges.push({ from: curKey, to: childKey });
      }
    }
  }

  return { nodes: [...nodes.values()], edges };
}

// Layout constants.
const NODE_W = 220;
const NODE_H = 60;
const V_GAP = 24;
const COL_GAP = 96;
const HEADER_H = 28;
const PAD = 24;

const GraphView = ({ graph, onNavigate }: { graph: Graph; onNavigate: () => void }) => {
  const okBg = useColorModeValue('white', 'gray.700');
  const okBorder = useColorModeValue('gray.300', 'gray.500');
  const missingBorder = useColorModeValue('red.300', 'red.500');
  const missingColor = useColorModeValue('red.500', 'red.300');
  const errorBorder = useColorModeValue('orange.300', 'orange.500');
  const errorColor = useColorModeValue('orange.500', 'orange.300');
  const headerColor = useColorModeValue('gray.500', 'gray.400');
  const edgeColor = useColorModeValue('#A0AEC0', '#718096');

  // Keep only the resource-type columns that actually have nodes, in LDS -> RDS -> CDS -> EDS order, so empty
  // columns do not leave gaps.
  const usedTypes = XDS_RESOURCE_TYPES.filter((type) => graph.nodes.some((n) => n.type === type));
  const colIndex = new Map(usedTypes.map((type, i) => [type, i]));

  // Assign each node a row within its column (in discovery order).
  const rowByKey = new Map<string, number>();
  const rowCountByType = new Map<XdsResourceType, number>();
  for (const node of graph.nodes) {
    const row = rowCountByType.get(node.type) ?? 0;
    rowByKey.set(node.key, row);
    rowCountByType.set(node.type, row + 1);
  }

  const pos = (node: GraphNode) => {
    const col = colIndex.get(node.type) ?? 0;
    const row = rowByKey.get(node.key) ?? 0;
    return { x: PAD + col * (NODE_W + COL_GAP), y: PAD + HEADER_H + row * (NODE_H + V_GAP) };
  };

  const maxRows = Math.max(1, ...usedTypes.map((type) => rowCountByType.get(type) ?? 0));
  const width = PAD * 2 + usedTypes.length * NODE_W + Math.max(0, usedTypes.length - 1) * COL_GAP;
  const height = PAD * 2 + HEADER_H + maxRows * (NODE_H + V_GAP);

  return (
    <Box position="relative" width={`${width}px`} height={`${height}px`} minW="100%">
      {/* Edges, drawn behind the nodes. */}
      <svg
        width={width}
        height={height}
        style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }}
      >
        <defs>
          <marker id="xds-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" fill={edgeColor} />
          </marker>
        </defs>
        {graph.edges.map((edge) => {
          const from = graph.nodes.find((n) => n.key === edge.from);
          const to = graph.nodes.find((n) => n.key === edge.to);
          if (!from || !to) {
            return null;
          }
          const a = pos(from);
          const b = pos(to);
          const x1 = a.x + NODE_W;
          const y1 = a.y + NODE_H / 2;
          const x2 = b.x;
          const y2 = b.y + NODE_H / 2;
          const dx = Math.max(40, (x2 - x1) / 2);
          return (
            <path
              key={`${edge.from}->${edge.to}`}
              d={`M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`}
              fill="none"
              stroke={edgeColor}
              strokeWidth={1.5}
              markerEnd="url(#xds-arrow)"
            />
          );
        })}
      </svg>

      {/* Column headers. */}
      {usedTypes.map((type) => (
        <Text
          key={type}
          position="absolute"
          left={`${PAD + (colIndex.get(type) ?? 0) * (NODE_W + COL_GAP)}px`}
          top={`${PAD - 4}px`}
          width={`${NODE_W}px`}
          fontSize="xs"
          fontWeight="bold"
          color={headerColor}
          letterSpacing="wide"
        >
          {XDS_RESOURCE_META[type].acronym}
        </Text>
      ))}

      {/* Nodes. */}
      {graph.nodes.map((node) => {
        const { x, y } = pos(node);
        const missing = node.status === 'missing';
        const errored = node.status === 'error';
        return (
          <Link
            as={RouteLink}
            href={resourceHref(node)}
            key={node.key}
            onClick={onNavigate}
            position="absolute"
            left={`${x}px`}
            top={`${y}px`}
            width={`${NODE_W}px`}
            height={`${NODE_H}px`}
            _hover={{ textDecoration: 'none', borderColor: 'teal.400', shadow: 'md' }}
            borderWidth="1px"
            borderStyle={missing || errored ? 'dashed' : 'solid'}
            borderColor={missing ? missingBorder : errored ? errorBorder : okBorder}
            borderRadius="md"
            bg={okBg}
            px={3}
            py={2}
            display="flex"
            flexDirection="column"
            justifyContent="center"
            overflow="hidden"
          >
            <Flex align="center" gap={2}>
              <Tag size="sm" colorScheme="purple" flexShrink={0}>
                {XDS_RESOURCE_META[node.type].acronym}
              </Tag>
              <Text fontWeight="semibold" isTruncated title={node.name}>
                {node.id}
              </Text>
            </Flex>
            {missing && (
              <Text fontSize="xs" color={missingColor}>
                not found
              </Text>
            )}
            {errored && (
              <Text fontSize="xs" color={errorColor}>
                failed to load
              </Text>
            )}
          </Link>
        );
      })}
    </Box>
  );
};

// A button that opens a modal visualizing the resource's downstream dependency graph.
export const ResourceGraph = ({
  group,
  type,
  id,
  k8s,
}: {
  group: string;
  type: XdsResourceType;
  id: string;
  k8s: boolean;
}) => {
  const dispatch = useAppDispatch();
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [graph, setGraph] = useState<Graph | null>(null);
  const [building, setBuilding] = useState(false);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    let cancelled = false;
    setBuilding(true);
    setGraph(null);
    buildGraph(dispatch, { group, type, id, k8s, name: `groups/${group}/${type}/${id}` })
      .then((result) => {
        if (!cancelled) {
          setGraph(result);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setBuilding(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, dispatch, group, type, id, k8s]);

  return (
    <>
      <Button leftIcon={<AiOutlinePartition />} variant="outline" colorScheme="teal" size="sm" onClick={onOpen}>
        View graph
      </Button>
      <Modal isOpen={isOpen} onClose={onClose} size="full" scrollBehavior="inside">
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Dependency graph · {`groups/${group}/${type}/${id}`}</ModalHeader>
          <ModalCloseButton />
          <ModalBody>
            {building ? (
              <Center py={20}>
                <Spinner size="lg" color="teal.400" />
              </Center>
            ) : graph && graph.nodes.length > 0 ? (
              <Box overflow="auto" pb={4}>
                <GraphView graph={graph} onNavigate={onClose} />
              </Box>
            ) : (
              <Center py={20}>
                <Text color="gray.500">No downstream references.</Text>
              </Center>
            )}
          </ModalBody>
        </ModalContent>
      </Modal>
    </>
  );
};
