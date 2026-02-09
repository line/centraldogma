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
import {
  Box,
  Button,
  Flex,
  HStack,
  IconButton,
  Menu,
  MenuButton,
  MenuItemOption,
  MenuList,
  MenuOptionGroup,
  Spacer,
  Tooltip,
  useDisclosure,
} from '@chakra-ui/react';
import { FcServices } from 'react-icons/fc';
import { ChakraLink } from 'dogma/common/components/ChakraLink';
import { ProjectDto } from 'dogma/features/project/ProjectDto';
import { DataTableClientPagination } from 'dogma/common/components/table/DataTableClientPagination';
import { createColumnHelper } from '@tanstack/react-table';
import { DateWithTooltip } from 'dogma/common/components/DateWithTooltip';
import { useMemo, useState } from 'react';
import { RestoreProject } from 'dogma/features/project/RestoreProject';
import { Deferred } from 'dogma/common/components/Deferred';
import { useAppDispatch, useAppSelector } from 'dogma/hooks';
import { isInternalProject } from 'dogma/util/repo-util';
import { LuFileWarning } from 'react-icons/lu';
import { CgFolderRemove } from 'react-icons/cg';
import { Author } from 'dogma/common/components/Author';
import { FiBox } from 'react-icons/fi';
import { FaFilter, FaTrashAlt } from 'react-icons/fa';
import { ChevronDownIcon } from '@chakra-ui/icons';
import { ProjectFilterType, setProjectFilter } from 'dogma/features/filter/filterSlice';
import { ProjectOwnersModal } from 'dogma/features/project/ProjectOwnersModal';
import { UserDto } from '../auth/UserDto';
import { UserRole } from '../../common/components/UserRole';

function filterProjects(projects: ProjectDto[], projectFilterType: ProjectFilterType, user: UserDto) {
  switch (projectFilterType) {
    case 'ALL':
      return projects;
    case 'MEMBER':
      return projects.filter((p) => p.userRole === 'MEMBER' || p.userRole === 'OWNER');
    case 'CREATOR':
      return projects.filter((p) => p.creator?.email === user.email);
  }
}

export const Projects = () => {
  const columnHelper = createColumnHelper<ProjectDto>();
  const dispatch = useAppDispatch();
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [ownersProjectName, setOwnersProjectName] = useState<string | null>(null);

  const { user, isInAnonymousMode } = useAppSelector((state) => state.auth);
  const { projectFilter, isInitialProjectFilter } = useAppSelector(({ filter }) => filter);
  const {
    data: projects,
    error,
    isLoading,
  } = useGetProjectsQuery({
    systemAdmin: user?.systemAdmin || false,
  });
  let filteredProjects = projects;
  if (!isInAnonymousMode && !isLoading && !error) {
    filteredProjects = filterProjects(projects, projectFilter, user);
    if (isInitialProjectFilter && projects.length > 0 && filteredProjects.length === 0) {
      // Render all projects if member projects are empty.
      dispatch(setProjectFilter('ALL'));
    }
  }

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
      columnHelper.accessor((row: ProjectDto) => row.userRole, {
        cell: (info) => UserRole({ role: info.getValue() }),
        header: 'Role',
      }),
      columnHelper.accessor((row: ProjectDto) => row.createdAt, {
        cell: (info) => info.getValue() && <DateWithTooltip date={info.getValue()} />,
        header: 'Created',
      }),
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) => {
          if (!info.row.original.createdAt) {
            return null;
          }
          return (
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setOwnersProjectName(info.getValue());
                onOpen();
              }}
            >
              View members
            </Button>
          );
        },
        header: 'Members',
        enableSorting: false,
      }),
      columnHelper.accessor((row: ProjectDto) => row.name, {
        cell: (info) => {
          if (isInternalProject(info.row.original.name)) {
            return null;
          }

          if (info.row.original.createdAt) {
            const userRole = info.row.original.userRole;
            if (userRole === 'OWNER') {
              return (
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
              );
            } else {
              // If the user is not an owner of the project, do not show the project settings button.
              return null;
            }
          }

          if (user.systemAdmin) {
            // Restore project button for system admin users.
            return <RestoreProject projectName={info.getValue()} />;
          } else {
            return null;
          }
        },
        header: 'Action',
        enableSorting: false,
      }),
    ],
    [columnHelper, onOpen, user],
  );
  return (
    <Deferred isLoading={isLoading} error={error}>
      {() => (
        <Box>
          {!isInAnonymousMode && (
            <Flex gap={2}>
              <Spacer />
              <Menu>
                <MenuButton as={Button} rightIcon={<ChevronDownIcon />} colorScheme="blue">
                  <HStack>
                    <FaFilter />
                    <Box>Type</Box>
                  </HStack>
                </MenuButton>
                <MenuList>
                  <MenuOptionGroup
                    defaultValue={projectFilter}
                    type="radio"
                    onChange={(type) => dispatch(setProjectFilter(type as ProjectFilterType))}
                    alignItems={'center'}
                  >
                    <MenuItemOption value="ALL">All</MenuItemOption>
                    <MenuItemOption value="MEMBER">I am a member</MenuItemOption>
                    <MenuItemOption value="CREATOR">Created by me</MenuItemOption>
                  </MenuOptionGroup>
                </MenuList>
              </Menu>
            </Flex>
          )}
          <DataTableClientPagination columns={columns} data={filteredProjects} />
          <ProjectOwnersModal
            projectName={ownersProjectName}
            isOpen={isOpen}
            onClose={() => {
              setOwnersProjectName(null);
              onClose();
            }}
          />
        </Box>
      )}
    </Deferred>
  );
};
