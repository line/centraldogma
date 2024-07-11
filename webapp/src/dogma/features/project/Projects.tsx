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
import { Box, HStack, IconButton, Tooltip } from '@chakra-ui/react';
import { FcServices } from 'react-icons/fc';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useMemo } from 'react';
import { RestoreProject } from 'dogma/features/project/RestoreProject';
import { Deferred } from 'dogma/common/components/Deferred';
import { useAppSelector } from 'dogma/hooks';
import { isInternalProject } from 'dogma/util/repo-util';
import { LuFileWarning } from 'react-icons/lu';
import { CgFolderRemove } from 'react-icons/cg';
import { Author } from 'dogma/common/components/Author';
import { FiBox } from 'react-icons/fi';
import { FaTrashAlt } from 'react-icons/fa';
import { WithProjectRole } from 'dogma/features/auth/ProjectRole';

export const Projects = () => {
  const { user } = useAppSelector((state) => state.auth);
  const { data: projects, error, isLoading } = useGetProjectsQuery({ admin: user?.admin || false });
  const columnHelper = createColumnHelper<ProjectDto>();
  const columns = useMemo(
    () => [
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) =>
          info.row.original.createdAt ? (
            <ChakraLink href={`/app/projects/${info.getValue()}`} fontWeight="bold">
              {isInternalProject(info.getValue()) ? (
                <HStack color="brown">
                  <Box>
                    <LuFileWarning />
                  </Box>
                  <Box>{info.getValue()}</Box>
                </HStack>
              ) : (
                <HStack>
                  <Box>
                    <FiBox />
                  </Box>
                  <Box>{info.getValue()}</Box>
                </HStack>
              )}
            </ChakraLink>
          ) : (
            <HStack color="gray">
              <Box>
                <CgFolderRemove />
              </Box>
              <Box>{info.getValue()}</Box>
            </HStack>
          ),
        header: 'Name',
      }),
      columnHelper.accessor((row: ProjectDto) => row.creator?.name, {
        cell: (info) =>
          info.getValue() ? (
            <Author name={info.getValue()} />
          ) : (
            <HStack>
              <Box>
                <FaTrashAlt />
              </Box>
              <Box>Removed</Box>
            </HStack>
          ),
        header: 'Creator',
      }),
      columnHelper.accessor((row: ProjectDto) => row.createdAt, {
        cell: (info) => info.getValue() && <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) =>
          isInternalProject(info.row.original.name) ? null : (
            <WithProjectRole projectName={info.row.original.name} roles={['OWNER', 'MEMBER']}>
              {() =>
                info.row.original.createdAt ? (
                  <ChakraLink href={`/app/projects/${info.getValue()}/settings`}>
                    <Tooltip label="Project settings" fontSize="md">
                      <IconButton
                        icon={<FcServices />}
                        variant="ghost"
                        colorScheme="teal"
                        aria-label="Project Settings"
                      />
                    </Tooltip>
                  </ChakraLink>
                ) : (
                  <RestoreProject projectName={info.getValue()} />
                )
              }
            </WithProjectRole>
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
