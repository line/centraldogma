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

import { ColumnDef, createColumnHelper } from '@tanstack/react-table';
import React, { useMemo } from 'react';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { Badge, Text } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { VariableDto } from 'dogma/features/project/settings/variables/VariableDto';
import { DeleteVariable } from 'dogma/features/project/settings/variables/DeleteVariable';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export type VariableListProps<Data extends object> = {
  projectName: string;
  repoName?: string;
  variables: VariableDto[];
  deleteVariable: (projectName: string, id: string, repoName?: string) => Promise<void>;
  isLoading: boolean;
};

const VariableList = <Data extends object>({
  projectName,
  repoName,
  variables,
  deleteVariable,
  isLoading,
}: VariableListProps<Data>) => {
  const columnHelper = createColumnHelper<VariableDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: VariableDto) => row.id, {
        cell: (info) => {
          const id = info.getValue() || 'undefined';
          const variableLink = repoName
            ? `/app/projects/${projectName}/repos/${repoName}/settings/variables/${info.row.original.id}`
            : `/app/projects/${projectName}/settings/variables/${info.row.original.id}`;
          return (
            <ChakraLink href={variableLink} fontWeight="semibold">
              {id}
            </ChakraLink>
          );
        },
        header: 'ID',
      }),
      columnHelper.accessor((row: VariableDto) => row.type, {
        cell: (info) => {
          if (info.getValue() === 'STRING') {
            return <Badge colorScheme="blue">{info.getValue()}</Badge>;
          } else {
            return <Badge colorScheme="green">{info.getValue()}</Badge>;
          }
        },
        header: 'Variable Type',
      }),
      columnHelper.accessor((row: VariableDto) => row.creation.user, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Modified By',
      }),
      columnHelper.accessor((row: VariableDto) => row.creation.timestamp, {
        cell: (info) => <DateWithTooltip date={info.getValue()} />,
        header: 'Modified At',
      }),
      columnHelper.accessor((row: VariableDto) => row.id, {
        cell: (info) => (
          <DeleteVariable
            projectName={projectName}
            repoName={repoName}
            id={info.getValue()}
            deleteVariable={deleteVariable}
            isLoading={isLoading}
          />
        ),
        header: 'Actions',
        enableSorting: false,
      }),
    ],
    [columnHelper, deleteVariable, isLoading, projectName, repoName],
  );
  return <DataTableClientPagination columns={columns as ColumnDef<VariableDto>[]} data={variables || []} />;
};

export default VariableList;
