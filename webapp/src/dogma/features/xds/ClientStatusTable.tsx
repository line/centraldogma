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
import { Badge, Box, Code, HStack, Tag, Text, VStack, Wrap, WrapItem } from '@chakra-ui/react';
import {
  ColumnDef,
  createColumnHelper,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { useMemo } from 'react';
import { DataTable } from 'dogma/features/xds/DataTable';
import { XdsAckStatus, XdsClientStatus, xdsTypeOf } from 'dogma/features/xds/ControlPlaneStatusDto';

// One row per (stream, resource type). A single ADS stream multiplexes several types, so it produces several
// rows.
interface ClientRow {
  streamId: number;
  nodeId: string;
  nodeCluster: string;
  appId: string;
  acronym: string;
  status: XdsAckStatus;
  nackReason: string;
  lastSeen: number;
  resourceNames: string[];
}

const columnHelper = createColumnHelper<ClientRow>();

function statusColorScheme(status: XdsAckStatus): string {
  switch (status) {
    case 'ACKED':
      return 'green';
    case 'NACKED':
      return 'red';
    default:
      return 'gray';
  }
}

function formatTime(millis: number): string {
  return millis > 0 ? new Date(millis).toLocaleString() : '-';
}

export const ClientStatusTable = ({ clients }: { clients: XdsClientStatus[] }) => {
  const rows = useMemo<ClientRow[]>(
    () =>
      clients.flatMap((client) =>
        client.types.map((type) => ({
          streamId: client.streamId,
          nodeId: client.nodeId,
          nodeCluster: client.nodeCluster,
          appId: client.appId,
          acronym: xdsTypeOf(type.typeUrl).acronym,
          status: type.status,
          nackReason: type.nackReason,
          lastSeen: type.lastSeen,
          resourceNames: type.resourceNames ?? [],
        })),
      ),
    [clients],
  );

  const columns = useMemo(() => {
    const cols: ColumnDef<ClientRow, unknown>[] = [
      columnHelper.accessor('nodeId', {
        header: 'Node',
        cell: (info) => (
          <VStack align="start" spacing={0}>
            <Text fontWeight="semibold">{info.getValue() || '(unknown)'}</Text>
            {info.row.original.nodeCluster && (
              <Text fontSize="xs" color="gray.500">
                {info.row.original.nodeCluster}
              </Text>
            )}
            <Text fontSize="xs" color="gray.400">
              stream #{info.row.original.streamId}
            </Text>
          </VStack>
        ),
      }),
      columnHelper.accessor('appId', {
        header: 'App ID',
        cell: (info) =>
          info.getValue() ? (
            <Code>{info.getValue()}</Code>
          ) : (
            <Text fontSize="sm" color="gray.400">
              anonymous
            </Text>
          ),
      }),
      columnHelper.accessor('acronym', {
        header: 'Type',
        cell: (info) => (
          <Tag colorScheme="purple" size="sm">
            {info.getValue()}
          </Tag>
        ),
      }),
      columnHelper.accessor((row) => row.resourceNames.join(','), {
        id: 'subscriptions',
        header: 'Subscriptions',
        enableSorting: false,
        cell: (info) => {
          const names = info.row.original.resourceNames;
          if (names.length === 0) {
            return (
              <Badge colorScheme="gray" variant="subtle">
                wildcard
              </Badge>
            );
          }
          return (
            <Wrap spacing={1}>
              {names.map((name) => (
                <WrapItem key={name}>
                  <Code fontSize="xs">{name}</Code>
                </WrapItem>
              ))}
            </Wrap>
          );
        },
      }),
      columnHelper.accessor('status', {
        header: 'Status',
        cell: (info) => (
          <Badge colorScheme={statusColorScheme(info.getValue() as XdsAckStatus)}>{info.getValue()}</Badge>
        ),
      }),
      columnHelper.accessor('nackReason', {
        header: 'NACK reason',
        enableSorting: false,
        cell: (info) =>
          info.getValue() ? (
            <Text color="red.500" fontSize="sm" maxW="md" whiteSpace="pre-wrap">
              {info.getValue()}
            </Text>
          ) : (
            <Text color="gray.400" fontSize="sm">
              -
            </Text>
          ),
      }),
      columnHelper.accessor((row) => row.lastSeen, {
        id: 'lastSeen',
        header: 'Last seen',
        cell: (info) => (
          <Text fontSize="sm" color="gray.600">
            {formatTime(info.row.original.lastSeen)}
          </Text>
        ),
      }),
    ];
    return cols;
  }, []);

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  if (rows.length === 0) {
    return (
      <Box mt={4}>
        <HStack color="gray.500">
          <Text>No xDS clients are currently connected to this server.</Text>
        </HStack>
      </Box>
    );
  }

  return <DataTable table={table} />;
};
