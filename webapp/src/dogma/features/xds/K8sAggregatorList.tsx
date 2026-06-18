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
import { Button, Flex, Link, Spacer, Text, useDisclosure } from '@chakra-ui/react';
import { default as RouteLink } from 'next/link';
import { AiOutlineDelete } from 'react-icons/ai';
import { IoAddCircleOutline } from 'react-icons/io5';
import {
  ColumnDef,
  createColumnHelper,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table';
import { useMemo, useState } from 'react';
import { DataTable } from 'dogma/features/xds/DataTable';
import { DeleteConfirmationModal } from 'dogma/common/components/DeleteConfirmationModal';
import { Deferred } from 'dogma/common/components/Deferred';
import { useDeleteK8sAggregatorMutation, useListK8sAggregatorsQuery } from 'dogma/features/xds/xdsApiSlice';
import { resourceName, XdsResourceDto } from 'dogma/features/xds/XdsTypes';
import { useGroupWriteAccess } from 'dogma/common/useGroupWriteAccess';
import { useAppDispatch } from 'dogma/hooks';
import { newNotification } from 'dogma/features/notification/notificationSlice';
import ErrorMessageParser from 'dogma/features/services/ErrorMessageParser';

const columnHelper = createColumnHelper<XdsResourceDto>();

function aggregatorHref(group: string, params: Record<string, string>): string {
  const search = new URLSearchParams({ group, ...params });
  return `/app/xds/k8s-aggregator?${search.toString()}`;
}

export const K8sAggregatorList = ({ group }: { group: string }) => {
  const dispatch = useAppDispatch();
  // Creating/deleting aggregators requires WRITE on the group, so those controls are hidden otherwise.
  const { hasWrite } = useGroupWriteAccess(group);
  const { data, isLoading, error } = useListK8sAggregatorsQuery({ group }, { refetchOnMountOrArgChange: true });
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [target, setTarget] = useState('');
  const [deleteAggregator, { isLoading: isDeleting }] = useDeleteK8sAggregatorMutation();

  const handleDelete = async () => {
    try {
      await deleteAggregator({ group, id: target }).unwrap();
      dispatch(newNotification('Aggregator deleted', `Aggregator '${target}' is deleted`, 'success'));
      onClose();
    } catch (err) {
      dispatch(newNotification('Failed to delete the aggregator', ErrorMessageParser.parse(err), 'error'));
    }
  };

  const columns = useMemo(() => {
    const cols: ColumnDef<XdsResourceDto, string>[] = [
      columnHelper.accessor('id', {
        header: 'Name',
        cell: (info) => (
          <Link as={RouteLink} href={aggregatorHref(group, { id: info.getValue() })} color="teal">
            {resourceName(group, info.row.original.path)}
          </Link>
        ),
      }),
    ];
    // The Action column only holds the Delete button, so it is shown only to users who can write. Editing is
    // done from the aggregator's own read-only view (click the name), matching the LDS/RDS/CDS/EDS resources.
    if (hasWrite) {
      cols.push(
        columnHelper.accessor('id', {
          id: 'action',
          header: 'Action',
          enableSorting: false,
          cell: (info) => (
            <Button
              leftIcon={<AiOutlineDelete />}
              colorScheme="red"
              variant="ghost"
              size="sm"
              onClick={() => {
                setTarget(info.getValue());
                onOpen();
              }}
            >
              Delete
            </Button>
          ),
        }),
      );
    }
    return cols;
  }, [group, onOpen, hasWrite]);

  // Memoized so the table receives a stable data reference across re-renders (react-table requires this).
  const aggregators = useMemo(() => data || [], [data]);
  const table = useReactTable({
    data: aggregators,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <>
          {hasWrite && (
            <Flex>
              <Spacer />
              <Button
                as={RouteLink}
                href={aggregatorHref(group, { action: 'new' })}
                colorScheme="teal"
                size="sm"
                leftIcon={<IoAddCircleOutline />}
              >
                New K8s Aggregator
              </Button>
            </Flex>
          )}
          {aggregators.length === 0 ? (
            <Text mt={4} color="gray.500">
              No Kubernetes endpoint aggregators in this group yet.
            </Text>
          ) : null}
          <DataTable table={table} />
          <DeleteConfirmationModal
            isOpen={isOpen}
            onClose={onClose}
            type="aggregator"
            id={target}
            from={group}
            handleDelete={handleDelete}
            isLoading={isDeleting}
          />
        </>
      )}
    </Deferred>
  );
};
