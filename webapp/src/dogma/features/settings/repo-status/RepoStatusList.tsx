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

import { createColumnHelper } from '@tanstack/react-table';
import { useMemo } from 'react';
import { Badge, Text } from '@chakra-ui/react';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepositoryStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';
import { MakeWritable } from 'dogma/features/settings/repo-status/MakeWritable';

export type RepoStatusListProps = {
  data: RepositoryStatus[];
};

const RepoStatusList = ({ data }: RepoStatusListProps) => {
  const columnHelper = createColumnHelper<RepositoryStatus>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row) => row.projectName, {
        cell: (info) => (
          <ChakraLink href={`/app/projects/${info.getValue()}`} fontWeight="semibold">
            {info.getValue()}
          </ChakraLink>
        ),
        header: 'Project',
        id: 'projectName',
      }),
      columnHelper.accessor((row) => row.repoName, {
        cell: (info) => {
          const { projectName, repoName } = info.row.original;
          return (
            <ChakraLink href={`/app/projects/${projectName}/repos/${repoName}/tree/head`} fontWeight="semibold">
              {repoName}
            </ChakraLink>
          );
        },
        header: 'Repository',
        id: 'repoName',
      }),
      columnHelper.accessor((row) => row.status, {
        cell: (info) => <Badge colorScheme="red">{info.getValue()}</Badge>,
        header: 'Status',
        id: 'status',
      }),
      columnHelper.accessor((row) => row.updatedAt, {
        cell: (info) => {
          const value = info.getValue();
          return value ? <DateWithTooltip date={value} /> : <Text>-</Text>;
        },
        header: 'Updated At',
        id: 'updatedAt',
      }),
      columnHelper.accessor((row) => row.repoName, {
        cell: (info) => (
          <MakeWritable projectName={info.row.original.projectName} repoName={info.row.original.repoName} />
        ),
        header: 'Actions',
        id: 'actions',
        enableSorting: false,
      }),
    ],
    [columnHelper],
  );

  return <DataTableClientPagination columns={columns} data={data} />;
};

export default RepoStatusList;
