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
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { RepositoryStatus } from 'dogma/features/settings/repo-status/RepoStatusDto';
import { MakeWritable } from 'dogma/features/settings/repo-status/MakeWritable';

// A project-scoped read-only entry uses "dogma" as its repository name.
const DOGMA_REPO = 'dogma';

export type RepoStatusListProps = {
  data: RepositoryStatus[];
};

const RepoStatusList = ({ data }: RepoStatusListProps) => {
  const columnHelper = createColumnHelper<RepositoryStatus>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row) => row.projectName, {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Project',
        id: 'projectName',
      }),
      columnHelper.accessor((row) => (row.repoName === DOGMA_REPO ? '-' : row.repoName), {
        cell: (info) => <Text>{info.getValue()}</Text>,
        header: 'Repository',
        id: 'repoName',
      }),
      columnHelper.accessor((row) => (row.repoName === DOGMA_REPO ? 'Project' : 'Repository'), {
        cell: (info) => <Badge colorScheme="purple">{info.getValue()}</Badge>,
        header: 'Scope',
        id: 'scope',
      }),
      columnHelper.accessor((row) => row.status, {
        cell: (info) => <Badge colorScheme="red">{info.getValue()}</Badge>,
        header: 'Status',
        id: 'status',
      }),
      columnHelper.accessor((row) => row.updatedAt, {
        cell: (info) => {
          const value = info.getValue();
          return <Text>{value ? new Date(value).toLocaleString() : '-'}</Text>;
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
