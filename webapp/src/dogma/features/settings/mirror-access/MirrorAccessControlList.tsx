/*
 * Copyright 2025 LINE Corporation
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
  useDeleteMirrorAccessControlMutation,
  useGetMirrorAccessControlsQuery,
} from 'dogma/features/api/apiSlice';
import { createColumnHelper } from '@tanstack/react-table';
import { MirrorAccessControl } from 'dogma/features/settings/mirror-access/MirrorAccessControl';
import React, { useMemo } from 'react';
import { Badge, Code, Text } from '@chakra-ui/react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DeleteMirrorAccessControl } from 'dogma/features/settings/mirror-access/DeleteMirrorAccess';

const MirrorAccessControlList = () => {
  const { data } = useGetMirrorAccessControlsQuery();
  const [deleteMirrorAccessControl, { isLoading }] = useDeleteMirrorAccessControlMutation();

  const columnHelper = createColumnHelper<MirrorAccessControl>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: MirrorAccessControl) => row.id, {
        cell: (info) => {
          return (
            <ChakraLink href={`/app/settings/mirror-access/${info.getValue()}`}>{info.getValue()}</ChakraLink>
          );
        },
        header: 'ID',
      }),
      columnHelper.accessor((row: MirrorAccessControl) => row.order, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Order',
      }),
      columnHelper.accessor((row: MirrorAccessControl) => row.targetPattern, {
        cell: (info) => {
          return <Code p={1.5}>{info.getValue()}</Code>;
        },
        header: 'URI Pattern',
      }),
      columnHelper.accessor((row: MirrorAccessControl) => row.allow, {
        cell: (info) => (
          <Badge colorScheme={info.getValue() ? 'blue' : 'red'}>
            {info.getValue() ? 'Allowed' : 'Disallowed'}
          </Badge>
        ),
        header: 'Access',
      }),
      columnHelper.accessor((row: MirrorAccessControl) => row.creation.user, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Created By',
      }),
      columnHelper.accessor((row: MirrorAccessControl) => row.id, {
        cell: (info) => (
          <DeleteMirrorAccessControl
            id={info.getValue()}
            deleteMirrorAccessControl={(id) => deleteMirrorAccessControl(id).unwrap()}
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteMirrorAccessControl, isLoading],
  );

  return <DataTableClientPagination columns={columns} data={data || []} />;
};

export default MirrorAccessControlList;
