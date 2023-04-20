/*
 * Copyright 2022 LINE Corporation
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
import { useGetProjectsQuery } from 'dogma/features/api/apiSlice';
import { IconButton } from '@chakra-ui/react';
import { FcServices } from 'react-icons/fc';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { useAppSelector } from 'dogma/store';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useMemo } from 'react';
import { RestoreProject } from 'dogma/features/project/RestoreProject';
import { Deferred } from 'dogma/common/components/Deferred';

export const Projects = () => {
  const { user } = useAppSelector((state) => state.auth);
  const {
    data: projects,
    error,
    isLoading,
  } = useGetProjectsQuery({ admin: user?.roles?.includes('LEVEL_ADMIN') || false });
  const columnHelper = createColumnHelper<ProjectDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) => (
          <ChakraLink href={`/app/projects/${info.getValue()}`} fontWeight="bold">
            {info.getValue()}
          </ChakraLink>
        ),
        header: 'Name',
      }),
      columnHelper.accessor((row: ProjectDto) => row.createdAt, {
        cell: (info) => info.getValue() && <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) =>
          info.row.original.createdAt ? (
            <ChakraLink href={`/app/projects/${info.getValue()}/metadata`}>
              <IconButton icon={<FcServices />} variant="ghost" colorScheme="teal" aria-label="metadata" />
            </ChakraLink>
          ) : (
            <RestoreProject projectName={info.getValue()} />
          ),
        header: 'Action',
        enableSorting: false,
      }),
    ],
    [columnHelper],
  );
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => <DataTableClientPagination columns={columns} data={projects} />}
    </Deferred>
  );
};
